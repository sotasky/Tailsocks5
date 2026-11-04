package tsproxy

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	tsp "github.com/0xKrito/tailscale-socks5-Android/tsproxy"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

func init() {
	if dir := os.Getenv("HOME"); dir != "" {
		os.Setenv("TMPDIR", filepath.Join(dir, "tsnet.tsproxy", "tmp"))
		os.Setenv("TS_LOGS_DIR", filepath.Join(dir, "tsnet.tsproxy", "logs"))
	} else {
		os.Setenv("TMPDIR", "/data/local/tmp")
		os.Setenv("TS_LOGS_DIR", "/data/local/tmp/tslogs")
	}
	// Android 没有 /etc/localtime，Go 回退到 UTC
	// 需要同时设 TZ (时区名) 和 ZONEINFO (zoneinfo 数据目录)
	if tz := os.Getenv("TZ"); tz == "" {
		// Try multiple Android timezone sources
		for _, p := range []string{
			"/data/misc/zoneinfo/current",
			"/system/usr/share/zoneinfo/current",
			"/persist/time/zone",
			"/etc/timezone",
		} {
			if link, err := os.Readlink(p); err == nil && link != "" {
				os.Setenv("TZ", link)
				break
			}
			if data, err := os.ReadFile(p); err == nil {
				tzStr := strings.TrimSpace(string(data))
				if tzStr != "" {
					os.Setenv("TZ", tzStr)
					break
				}
			}
		}
		// Last resort: parse persist.sys.timezone from build.prop
		if os.Getenv("TZ") == "" {
			if data, err := os.ReadFile("/system/build.prop"); err == nil {
				for _, line := range strings.Split(string(data), "\n") {
					line = strings.TrimSpace(line)
					if strings.HasPrefix(line, "persist.sys.timezone=") {
						os.Setenv("TZ", strings.TrimPrefix(line, "persist.sys.timezone="))
						break
					}
				}
			}
		}
	}

	// Set ZONEINFO so Go can find the timezone data files on Android
	for _, zidir := range []string{
		"/data/misc/zoneinfo",
		"/system/usr/share/zoneinfo",
	} {
		if info, err := os.Stat(zidir); err == nil && info.IsDir() {
			os.Setenv("ZONEINFO", zidir)
			break
		}
	}

	log.Println("[init] TZ =", os.Getenv("TZ"), "ZONEINFO =", os.Getenv("ZONEINFO"))

	// Load timezone location for log timestamps
	// time.Local is already initialized by Go runtime before init() runs,
	// so setting TZ env var won't affect time.Now(). We must load explicitly.
	if tz := os.Getenv("TZ"); tz != "" {
		if loc, err := time.LoadLocation(tz); err == nil {
			localTZ = loc
			log.Println("[init] Loaded timezone:", tz)
		} else {
			log.Println("[init] Failed to load timezone:", err)
		}
	}
	if localTZ == nil {
		localTZ = time.Local // fallback to whatever Go detected
	}
}

// global state
var (
	mu       sync.Mutex
	server   *tsnet.Server
	starting *tsnet.Server // boot in progress, not yet published as `server`
	proxy    *tsp.TsProxy
	running  bool
	loginURL string
	// generation invalidates a Start that was superseded by Stop (or by a new
	// Start while the previous one was still booting). Every bootServer and
	// login watcher captures the generation it was started with.
	generation int
	logBuf     = newLogBuffer(300)
	localTZ    *time.Location
)

type logBuffer struct {
	mu   sync.Mutex
	msgs []string
	max  int
	fn   func(string)
}

func newLogBuffer(max int) *logBuffer { return &logBuffer{max: max} }

func (lb *logBuffer) write(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	ts := time.Now().In(localTZ).Format("15:04:05.000")
	timestamped := fmt.Sprintf("[%s] %s", ts, msg)
	lb.mu.Lock()
	lb.msgs = append(lb.msgs, timestamped)
	if len(lb.msgs) > lb.max {
		lb.msgs = lb.msgs[len(lb.msgs)-lb.max:]
	}
	fn := lb.fn
	lb.mu.Unlock()
	log.Println(msg) // logcat already has timestamps
	if fn != nil {
		fn(timestamped)
	}
}

func (lb *logBuffer) clear() {
	lb.mu.Lock()
	lb.msgs = nil
	lb.mu.Unlock()
}

func (lb *logBuffer) snapshot() []string {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	out := make([]string, len(lb.msgs))
	copy(out, lb.msgs)
	return out
}

func dataDir(hostname string) string {
	if d := os.Getenv("HOME"); d != "" {
		return filepath.Join(d, "tsnet.tsproxy", hostname)
	}
	return "/data/local/tmp/tsnet.tsproxy/" + hostname
}

func Start(socksAddr, hostname, tsnetDir string) string {
	mu.Lock()
	defer mu.Unlock()

	if running || starting != nil {
		logBuf.write("Already running")
		return ""
	}

	// A previous account's auth URL must not leak into this one.
	loginURL = ""

	if socksAddr == "" {
		socksAddr = "127.0.0.1:1080"
	}
	if hostname == "" {
		hostname = "ts-proxy-android"
	}

	if tsnetDir == "" || tsnetDir == "DEFAULT" {
		tsnetDir = dataDir(hostname)
	}

	logBuf.write("Starting socks=%s host=%s dir=%s", socksAddr, hostname, tsnetDir)

	if err := os.MkdirAll(tsnetDir, 0755); err != nil {
		return "ERROR: Cannot create dir: " + err.Error()
	}

	logsDir := filepath.Join(tsnetDir, "logs")
	tmpDir := filepath.Join(tsnetDir, "tmp")
	for _, d := range []string{logsDir, tmpDir} {
		if err := os.MkdirAll(d, 0755); err != nil {
			return "ERROR: Cannot create " + d + ": " + err.Error()
		}
	}
	os.Setenv("TS_LOGS_DIR", logsDir)
	os.Setenv("TMPDIR", tmpDir)
	logBuf.write("Env: TS_LOGS_DIR=%s TMPDIR=%s", logsDir, tmpDir)

	logBuf.write("Testing net.Listen on %s...", socksAddr)
	// Wait briefly for port to release if previous instance is still closing
	time.Sleep(200 * time.Millisecond)
	testListener, err := net.Listen("tcp", socksAddr)
	if err != nil {
		logBuf.write("net.Listen FAILED: %v", err)
		return "ERROR: Cannot bind " + socksAddr + ": " + err.Error()
	}
	logBuf.write("net.Listen OK, closing test listener")
	testListener.Close()

	srv := &tsnet.Server{
		Hostname: hostname,
		Dir:      tsnetDir,
		Logf: func(format string, args ...any) {
			logBuf.write("[tsnet] "+format, args...)
		},
	}

	generation++
	gen := generation
	starting = srv
	go bootServer(srv, socksAddr, gen)

	return ""
}

func bootServer(srv *tsnet.Server, socksAddr string, gen int) {
	defer func() {
		if r := recover(); r != nil {
			logBuf.write("PANIC: %v", r)
		}
		mu.Lock()
		if starting == srv {
			starting = nil
		}
		mu.Unlock()
	}()

	logBuf.write("Step 1: Initializing tsnet...")
	if err := srv.Start(); err != nil {
		logBuf.write("srv.Start error: %v", err)
		return
	}
	logBuf.write("Step 1: Done")

	go watchLoginURL(srv, gen)

	logBuf.write("Step 2: Creating TsProxy (will wait for auth)...")
	p, err := tsp.NewTsProxy(srv, 1100, 330, true)
	if err != nil {
		logBuf.write("NewTsProxy error: %v", err)
		srv.Close()
		return
	}
	logBuf.write("Step 2: TsProxy created")

	tsp.SetSocksLogf(logBuf.write)

	logBuf.write("Step 3: Serving SOCKS5 on %s", socksAddr)
	go func() {
		defer func() {
			if r := recover(); r != nil {
				logBuf.write("SOCKS panic: %v", r)
			}
		}()
		logBuf.write("About to call p.ServeSOCKS(%s, ...)", socksAddr)
		p.ServeSOCKS(socksAddr, "", "", "", "")
		logBuf.write("p.ServeSOCKS returned")
	}()

	time.Sleep(500 * time.Millisecond)

	logBuf.write("Verifying SOCKS5 port...")
	verifyConn, err := net.DialTimeout("tcp", socksAddr, 2*time.Second)
	if err != nil {
		logBuf.write("WARNING: Cannot connect to SOCKS5: %v", err)
	} else {
		verifyConn.Close()
		logBuf.write("SOCKS5 port is listening")
	}

	mu.Lock()
	if generation != gen {
		// Stop() (or a newer Start) happened while we were booting. Do not
		// resurrect this server.
		mu.Unlock()
		logBuf.write("Start superseded, closing tsnet")
		srv.Close()
		return
	}
	server = srv
	proxy = p
	running = true
	loginURL = ""
	mu.Unlock()
	logBuf.write("ts-proxy is running!")
}

// watchLoginURL follows the IPN bus for the lifetime of the server so that
// re-authentication (not just the initial login) can surface a BrowseToURL.
func watchLoginURL(srv *tsnet.Server, gen int) {
	lc, err := srv.LocalClient()
	if err != nil {
		logBuf.write("LocalClient (watch): %v", err)
		return
	}
	watcher, err := lc.WatchIPNBus(context.Background(), ipn.NotifyInitialState)
	if err != nil {
		logBuf.write("WatchIPNBus error: %v", err)
		return
	}
	defer watcher.Close()
	for {
		n, err := watcher.Next()
		if err != nil {
			return
		}
		mu.Lock()
		stale := generation != gen
		mu.Unlock()
		if stale {
			return
		}
		if url := n.BrowseToURL; url != nil && *url != "" {
			mu.Lock()
			loginURL = *url
			mu.Unlock()
			logBuf.write("LOGIN URL: %s", *url)
		}
		if n.State != nil && *n.State == ipn.Running {
			mu.Lock()
			loginURL = ""
			mu.Unlock()
		}
	}
}

func GetLoginURL() string {
	mu.Lock()
	defer mu.Unlock()
	return loginURL
}

func Stop() {
	mu.Lock()

	if !running && server == nil && starting == nil {
		generation++ // cancel an in-flight Start that has not published itself yet
		mu.Unlock()
		logBuf.write("Not running")
		return
	}
	logBuf.write("Stopping...")

	// Close tsnet server first (releases its network resources)
	srv := server
	pending := starting
	server = nil
	starting = nil
	proxy = nil
	running = false
	loginURL = ""
	generation++ // invalidate any in-flight boot / login watcher
	mu.Unlock()

	if srv != nil {
		srv.Close()
		logBuf.write("tsnet closed")
	}
	// A boot that was still waiting for authorization has no `server` yet;
	// close it too so it does not resurrect later.
	if pending != nil && pending != srv {
		pending.Close()
		logBuf.write("pending tsnet closed")
	}

	// Shut down SOCKS5 server synchronously (closes TCP+UDP listeners, frees port)
	// Must wait for this to finish before next Start() can bind the port
	tsp.StopSocks5()

	logBuf.write("Stopped")
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

func GetTailscaleIP() string {
	mu.Lock()
	defer mu.Unlock()
	if server == nil {
		return ""
	}
	lc, err := server.LocalClient()
	if err != nil {
		return ""
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		return ""
	}
	for _, ip := range st.Self.TailscaleIPs {
		if ip.Is4() {
			return ip.String()
		}
	}
	return ""
}

// GetAccountName returns the tailnet account of the logged-in node (usually
// the login email). Returns "" when not running or not logged in yet.
func GetAccountName() string {
	mu.Lock()
	srv := server
	mu.Unlock()
	if srv == nil {
		return ""
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return ""
	}
	st, err := lc.Status(context.Background())
	if err != nil || st.Self == nil {
		return ""
	}
	if u, ok := st.User[st.Self.UserID]; ok {
		if u.LoginName != "" {
			return u.LoginName
		}
		return u.DisplayName
	}
	return ""
}

// accountInfo is a flat view of the signed-in account. gomobile bindings only
// carry basic types, so this crosses the JNI boundary as JSON.
type accountInfo struct {
	LoggedIn     bool   `json:"loggedIn"`
	LoginName    string `json:"loginName"`
	DisplayName  string `json:"displayName"`
	TailnetName  string `json:"tailnetName"`
	NodeName     string `json:"nodeName"`
	KeyExpiry    string `json:"keyExpiry"`
	KeyExpired   bool   `json:"keyExpired"`
	TailscaleIP  string `json:"tailscaleIP"`
	BackendState string `json:"backendState"`
	LoginURL     string `json:"loginUrl"`
	Dir          string `json:"dir"`
}

// GetAccountInfo returns the current account (email, name, key expiry, ...) as
// JSON. Returns "{}" when the server is not running.
func GetAccountInfo() string {
	mu.Lock()
	srv := server
	mu.Unlock()
	if srv == nil {
		return "{}"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "{}"
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		return "{}"
	}

	loc := localTZ
	if loc == nil {
		loc = time.Local
	}

	info := accountInfo{
		BackendState: st.BackendState,
		// Lets the UI verify that this status belongs to the account whose
		// directory it just started (old status can lag during a switch).
		Dir: srv.Dir,
	}
	if st.Self != nil {
		info.NodeName = st.Self.HostName
		for _, ip := range st.Self.TailscaleIPs {
			if ip.Is4() {
				info.TailscaleIP = ip.String()
				break
			}
		}
		if st.Self.KeyExpiry != nil {
			info.KeyExpiry = st.Self.KeyExpiry.In(loc).Format("2006-01-02 15:04")
			info.KeyExpired = time.Now().After(*st.Self.KeyExpiry)
		}
		if u, ok := st.User[st.Self.UserID]; ok {
			info.LoginName = u.LoginName
			info.DisplayName = u.DisplayName
		}
	}
	if st.CurrentTailnet != nil {
		info.TailnetName = st.CurrentTailnet.Name
		if info.TailnetName == "" {
			info.TailnetName = st.CurrentTailnet.MagicDNSSuffix
		}
	}
	if info.TailnetName == "" {
		// Older/edge responses may only carry the top-level MagicDNS suffix.
		info.TailnetName = st.MagicDNSSuffix
	}
	info.LoggedIn = info.LoginName != "" || info.DisplayName != ""

	if st.AuthURL != "" && !info.LoggedIn {
		// Keep the shared login URL in sync even if the watcher missed it.
		mu.Lock()
		loginURL = st.AuthURL
		mu.Unlock()
	}
	info.LoginURL = GetLoginURL()

	out, err := json.Marshal(info)
	if err != nil {
		return "{}"
	}
	return string(out)
}

// SetHostname updates the device name of the running node without a new
// login. Used by the authentication card so the name can be chosen before the
// first authorization completes.
func SetHostname(name string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		return "ERROR: 设备名不能为空"
	}
	mu.Lock()
	srv := server
	if srv == nil {
		// The node can still be waiting for its first authorization; update its
		// prefs so the name chosen on the auth card takes effect.
		srv = starting
	}
	mu.Unlock()
	if srv == nil {
		return "ERROR: 未启动"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "ERROR: " + err.Error()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if _, err := lc.EditPrefs(ctx, &ipn.MaskedPrefs{
		Prefs:       ipn.Prefs{Hostname: name},
		HostnameSet: true,
	}); err != nil {
		return "ERROR: " + err.Error()
	}
	logBuf.write("Hostname set to %s", name)
	return ""
}

// Logout signs the current node out of the tailnet, then stops the proxy. The
// tsnet data directory is kept so the same account slot can be re-authenticated
// later; the next Start on that directory produces a fresh login URL.
func Logout() string {
	mu.Lock()
	srv := server
	mu.Unlock()
	if srv == nil {
		// The node may still be booting and waiting for its first login; make
		// sure that pending node is cancelled too.
		Stop()
		return ""
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "ERROR: " + err.Error()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	errMsg := ""
	if err := lc.Logout(ctx); err != nil {
		// Local state is already marked logged out even if the network call
		// fails, so stop the node regardless and report the error.
		errMsg = "ERROR: " + err.Error()
	}
	logBuf.write("Logged out")
	Stop()
	return errMsg
}

// Reauthenticate asks the control server for an interactive login URL for the
// current node (e.g. when the node key expired). The URL arrives asynchronously
// through the IPN bus watcher and is exposed via GetLoginURL.
func Reauthenticate() string {
	mu.Lock()
	srv := server
	mu.Unlock()
	if srv == nil {
		return "ERROR: 未启动"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "ERROR: " + err.Error()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := lc.StartLoginInteractive(ctx); err != nil {
		return "ERROR: " + err.Error()
	}
	logBuf.write("Re-authentication requested")
	return ""
}

// peerInfo is a flat view of one tailnet peer. gomobile bindings only carry
// basic types, so the peer list crosses the JNI boundary as a JSON array.
type peerInfo struct {
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	HostName       string   `json:"hostName"`
	DNSName        string   `json:"dnsName"`
	OS             string   `json:"os"`
	IPs            []string `json:"ips"`
	Tags           []string `json:"tags"`
	Online         bool     `json:"online"`
	Active         bool     `json:"active"`
	CurAddr        string   `json:"curAddr"`
	Relay          string   `json:"relay"`
	PeerRelay      string   `json:"peerRelay"`
	RxBytes        int64    `json:"rxBytes"`
	TxBytes        int64    `json:"txBytes"`
	ExitNode       bool     `json:"exitNode"`
	ExitNodeOption bool     `json:"exitNodeOption"`
	Expired        bool     `json:"expired"`
	LastSeen       string   `json:"lastSeen"`
	UserName       string   `json:"userName"`
	Self           bool     `json:"self"`
}

// makePeerInfo flattens one status entry for the UI. self marks this device.
func makePeerInfo(st *ipnstate.Status, p *ipnstate.PeerStatus, loc *time.Location, self bool) peerInfo {
	ips := make([]string, 0, len(p.TailscaleIPs))
	for _, ip := range p.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	user := ""
	if u, ok := st.User[p.UserID]; ok {
		user = u.DisplayName
		if user == "" {
			user = u.LoginName
		}
	}
	lastSeen := ""
	if !p.LastSeen.IsZero() {
		lastSeen = p.LastSeen.In(loc).Format("2006-01-02 15:04")
	}
	// Admin-console "machine name" is the first label of the MagicDNS
	// name (it can be renamed there); HostName is the OS hostname.
	dnsName := strings.TrimSuffix(p.DNSName, ".")
	name := strings.SplitN(dnsName, ".", 2)[0]
	if name == "" {
		name = p.HostName
	}
	online := p.Online
	if self {
		// This device is by definition online whenever we can report status.
		online = true
	}
	var tags []string
	if p.Tags != nil {
		tags = p.Tags.AsSlice()
	}
	return peerInfo{
		ID:             string(p.ID),
		Name:           name,
		HostName:       p.HostName,
		DNSName:        dnsName,
		OS:             p.OS,
		IPs:            ips,
		Tags:           tags,
		Online:         online,
		Active:         p.Active,
		CurAddr:        p.CurAddr,
		Relay:          p.Relay,
		PeerRelay:      p.PeerRelay,
		RxBytes:        p.RxBytes,
		TxBytes:        p.TxBytes,
		ExitNode:       p.ExitNode,
		ExitNodeOption: p.ExitNodeOption,
		Expired:        p.Expired,
		LastSeen:       lastSeen,
		UserName:       user,
		Self:           self,
	}
}

// GetPeers returns the tailnet peers visible to this node as a JSON array.
// Returns "[]" when the server is not running or the status is unavailable.
func GetPeers() string {
	mu.Lock()
	srv := server
	mu.Unlock()
	if srv == nil {
		return "[]"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "[]"
	}
	st, err := lc.Status(context.Background())
	if err != nil {
		return "[]"
	}

	loc := localTZ
	if loc == nil {
		loc = time.Local
	}

	peers := make([]peerInfo, 0, len(st.Peer)+1)
	if st.Self != nil {
		peers = append(peers, makePeerInfo(st, st.Self, loc, true))
	}
	for _, p := range st.Peer {
		if p.ShareeNode {
			// Nodes shared into this tailnet by another user; hidden by
			// default in most tailscale clients.
			continue
		}
		peers = append(peers, makePeerInfo(st, p, loc, false))
	}
	sort.Slice(peers, func(i, j int) bool {
		if peers[i].Self != peers[j].Self {
			return peers[i].Self
		}
		if peers[i].Online != peers[j].Online {
			return peers[i].Online
		}
		return strings.ToLower(peers[i].Name) < strings.ToLower(peers[j].Name)
	})

	out, err := json.Marshal(peers)
	if err != nil {
		return "[]"
	}
	return string(out)
}

// pingResult is one disco ping sample, encoded as JSON for the UI.
type pingResult struct {
	LatencyMs  float64 `json:"latencyMs"`
	Endpoint   string  `json:"endpoint"`
	PeerRelay  string  `json:"peerRelay"`
	DERPRegion string  `json:"derpRegion"`
	Err        string  `json:"err"`
}

func pingError(msg string) string {
	out, _ := json.Marshal(pingResult{Err: msg})
	return string(out)
}

// PingPeer sends a single disco ping to the given Tailscale IP and returns the
// result as JSON. Callers loop it to gather several samples; Tailscale has no
// "ping all" LocalAPI, pings always target one peer at a time.
func PingPeer(ipStr string) string {
	addr, err := netip.ParseAddr(strings.TrimSpace(ipStr))
	if err != nil {
		return pingError("无效的 IP")
	}
	mu.Lock()
	srv := server
	mu.Unlock()
	if srv == nil {
		return pingError("未启动")
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return pingError(err.Error())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	res, err := lc.Ping(ctx, addr, tailcfg.PingDisco)
	if err != nil {
		return pingError(err.Error())
	}
	if res.Err != "" {
		return pingError(res.Err)
	}
	logBuf.write("[ping] %s endpoint=%q peerRelay=%q derp=%q latency=%.1fms",
		ipStr, res.Endpoint, res.PeerRelay, res.DERPRegionCode, float64(res.LatencySeconds)*1000)
	out, err := json.Marshal(pingResult{
		LatencyMs:  float64(res.LatencySeconds) * 1000,
		Endpoint:   res.Endpoint,
		PeerRelay:  res.PeerRelay,
		DERPRegion: res.DERPRegionCode,
	})
	if err != nil {
		return pingError(err.Error())
	}
	return string(out)
}

// GetLogs returns all accumulated log messages as a single string.
func GetLogs() string {
	return strings.Join(logBuf.snapshot(), "\n")
}

// ClearLogs removes all log messages.
func ClearLogs() {
	logBuf.clear()
}

func SetLogCallback(fn func(string)) {
	logBuf.mu.Lock()
	defer logBuf.mu.Unlock()
	logBuf.fn = fn
}
