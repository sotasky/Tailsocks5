package tsproxy

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "tailscale.com/feature/taildrop"
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"tailscale.com/tailcfg/nodecap"
	"tailscale.com/tsnet"
)

// Taildrop support.
//
// tsnet deliberately does not link the taildrop feature (see tsnet/tsnet.go),
// so the blank import above is what registers the extension and the
// /localapi/v0/file-* handlers. On Android the upstream filesystem FileOps
// implementation is excluded by a build tag, so android-patch adds it back;
// received files then land in <tsnet dir>/files/<login>-uid-<uid> and are
// enumerated through the LocalAPI (WaitingFiles / GetWaitingFile /
// DeleteWaitingFile).
//
// Kotlin never sees those staging files directly: SaveWaitingFile streams one
// of them into the app cache, the Android side publishes it to Download or a
// SAF folder, and then DeleteWaitingFile removes the staging copy.

func currentServer() *tsnet.Server {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		return server
	}
	// The node is still booting / waiting for its first authorization; the
	// LocalAPI is already usable once Start() has run.
	return starting
}

// taildropInfo describes whether Taildrop can be used right now.
type taildropInfo struct {
	Running      bool   `json:"running"`
	LoggedIn     bool   `json:"loggedIn"`
	AdminEnabled bool   `json:"adminEnabled"`
	BackendState string `json:"backendState"`
	Dir          string `json:"dir"`
	Error        string `json:"error"`
}

// GetTaildropInfo reports whether the node is connected and whether the
// tailnet has the "Send Files" feature enabled. Both sending and receiving
// require nodecap.FileSharing on this node.
func GetTaildropInfo() string {
	srv := currentServer()
	if srv == nil {
		out, _ := json.Marshal(taildropInfo{})
		return string(out)
	}
	lc, err := srv.LocalClient()
	if err != nil {
		out, _ := json.Marshal(taildropInfo{Error: err.Error()})
		return string(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		out, _ := json.Marshal(taildropInfo{Error: err.Error()})
		return string(out)
	}
	info := taildropInfo{
		Running:      st.BackendState == ipn.Running.String(),
		BackendState: st.BackendState,
		Dir:          srv.Dir,
	}
	if st.Self != nil {
		info.LoggedIn = true
		for _, c := range st.Self.Capabilities {
			if c == nodecap.FileSharing {
				info.AdminEnabled = true
				break
			}
		}
	}
	out, _ := json.Marshal(info)
	return string(out)
}

type fileTarget struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	DNSName string `json:"dnsName"`
	OS      string `json:"os"`
	Online  bool   `json:"online"`
}

type fileTargetsReply struct {
	Targets []fileTarget `json:"targets"`
	Error   string       `json:"error"`
}

// GetFileTargets returns the devices that can receive Taildrop files. Devices
// are listed even when offline so the UI can show (and disable) them; the
// admin console must have "Send Files" enabled or the list is unavailable.
func GetFileTargets() string {
	srv := currentServer()
	if srv == nil {
		out, _ := json.Marshal(fileTargetsReply{Targets: []fileTarget{}, Error: "未启动"})
		return string(out)
	}
	lc, err := srv.LocalClient()
	if err != nil {
		out, _ := json.Marshal(fileTargetsReply{Targets: []fileTarget{}, Error: err.Error()})
		return string(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	selfID := tailcfg.StableNodeID("")
	if st, err := lc.Status(ctx); err == nil && st.Self != nil {
		selfID = st.Self.ID
	}

	list, err := lc.FileTargets(ctx)
	if err != nil {
		out, _ := json.Marshal(fileTargetsReply{Targets: []fileTarget{}, Error: friendlyTaildropError(err)})
		return string(out)
	}

	targets := make([]fileTarget, 0, len(list))
	for _, t := range list {
		if t.Node == nil {
			continue
		}
		n := t.Node
		if n.StableID == selfID {
			continue
		}
		name := n.ComputedName
		if name == "" {
			name = strings.TrimSuffix(n.Name, ".")
			if i := strings.IndexByte(name, '.'); i > 0 {
				name = name[:i]
			}
		}
		targets = append(targets, fileTarget{
			ID:      string(n.StableID),
			Name:    name,
			DNSName: strings.TrimSuffix(n.Name, "."),
			OS:      n.Hostinfo.OS(),
			Online:  n.Online != nil && *n.Online,
		})
	}
	out, _ := json.Marshal(fileTargetsReply{Targets: targets})
	return string(out)
}

// ---------------------------------------------------------------- sending

type transferJob struct {
	Name  string `json:"name"`
	Total int64  `json:"total"`
	Sent  int64  `json:"sent"`
	Done  bool   `json:"done"`
	Error string `json:"error"`
	mu    sync.Mutex
}

func (j *transferJob) add(n int64) {
	j.mu.Lock()
	j.Sent += n
	j.mu.Unlock()
}

func (j *transferJob) snapshot() transferJob {
	j.mu.Lock()
	defer j.mu.Unlock()
	return transferJob{
		Name:  j.Name,
		Total: j.Total,
		Sent:  j.Sent,
		Done:  j.Done,
		Error: j.Error,
	}
}

type transfersReply struct {
	Active bool          `json:"active"`
	Jobs   []transferJob `json:"jobs"`
}

var (
	sendMu     sync.Mutex
	sendJobs   []*transferJob
	sendActive bool
	sendCancel context.CancelFunc
	// sendEpoch protects the cleanup of a cancelled batch from clobbering a
	// newer batch that started after an account switch.
	sendEpoch int
)

// SendFiles queues files for delivery to one device. pathsJSON is a JSON
// array of local file paths (the Android side copies shared content URIs into
// the app cache first). The transfer keeps running in the background; poll
// GetTransfers.
func SendFiles(targetID, pathsJSON string) string {
	if strings.TrimSpace(targetID) == "" {
		return "ERROR: 目标设备为空"
	}
	var paths []string
	if err := json.Unmarshal([]byte(pathsJSON), &paths); err != nil {
		return "ERROR: 文件列表无效"
	}
	if len(paths) == 0 {
		return "ERROR: 没有要发送的文件"
	}
	srv := currentServer()
	if srv == nil {
		return "ERROR: 未启动"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "ERROR: " + err.Error()
	}

	sendMu.Lock()
	if sendActive {
		sendMu.Unlock()
		return "ERROR: 已有发送任务在进行"
	}
	ctx, cancel := context.WithCancel(context.Background())
	jobs := make([]*transferJob, 0, len(paths))
	for _, p := range paths {
		jobs = append(jobs, &transferJob{Name: filepath.Base(p)})
	}
	sendJobs = jobs
	sendActive = true
	sendCancel = cancel
	sendEpoch++
	epoch := sendEpoch
	sendMu.Unlock()

	go runSend(ctx, lc, tailcfg.StableNodeID(targetID), paths, jobs, epoch)
	return ""
}

func runSend(ctx context.Context, lc localPusher, target tailcfg.StableNodeID, paths []string, jobs []*transferJob, epoch int) {
	defer func() {
		sendMu.Lock()
		if sendEpoch == epoch {
			sendActive = false
			sendCancel = nil
		}
		sendMu.Unlock()
	}()

	for i, p := range paths {
		job := jobs[i]
		if ctx.Err() != nil {
			markJob(job, "已取消")
			continue
		}
		f, err := os.Open(p)
		if err != nil {
			markJob(job, err.Error())
			continue
		}
		st, err := f.Stat()
		if err != nil || st.IsDir() {
			f.Close()
			markJob(job, "不是普通文件")
			continue
		}
		job.mu.Lock()
		job.Total = st.Size()
		job.mu.Unlock()

		err = lc.PushFile(ctx, target, st.Size(), filepath.Base(p), &progressReader{r: f, job: job})
		f.Close()
		if err != nil {
			markJob(job, friendlyTaildropError(err))
			continue
		}
		job.mu.Lock()
		job.Sent = job.Total
		job.Done = true
		job.mu.Unlock()
	}
}

// localPusher is the subset of *local.Client used here.
type localPusher interface {
	PushFile(ctx context.Context, target tailcfg.StableNodeID, size int64, name string, r io.Reader) error
}

type progressReader struct {
	r   io.Reader
	job *transferJob
}

func (p *progressReader) Read(b []byte) (int, error) {
	n, err := p.r.Read(b)
	if n > 0 {
		p.job.add(int64(n))
	}
	return n, err
}

func markJob(job *transferJob, msg string) {
	job.mu.Lock()
	job.Done = true
	job.Error = msg
	job.mu.Unlock()
}

func friendlyTaildropError(err error) string {
	msg := err.Error()
	switch {
	case strings.Contains(msg, "file sharing not enabled"):
		return "Tailscale 管理后台未开启 Send Files"
	case strings.Contains(msg, "not connected to the tailnet"):
		return "未连接到 Tailnet"
	case errors.Is(err, context.Canceled):
		return "已取消"
	}
	return msg
}

// GetTransfers returns the current/last batch of outgoing transfers.
func GetTransfers() string {
	sendMu.Lock()
	jobs := make([]transferJob, 0, len(sendJobs))
	for _, j := range sendJobs {
		jobs = append(jobs, j.snapshot())
	}
	active := sendActive
	sendMu.Unlock()
	out, _ := json.Marshal(transfersReply{Active: active, Jobs: jobs})
	return string(out)
}

// ResetTransfers clears the last completed batch so the UI can distinguish a
// new send from an old one. No-op while a transfer is active.
func ResetTransfers() string {
	sendMu.Lock()
	if !sendActive {
		sendJobs = nil
	}
	sendMu.Unlock()
	return ""
}

// CancelTransfers cancels the running batch, if any.
func CancelTransfers() string {
	sendMu.Lock()
	cancel := sendCancel
	sendMu.Unlock()
	if cancel != nil {
		cancel()
	}
	return ""
}

// cancelTransfersLocked is called from Stop while mu is not held.
func cancelTransfersLocked() {
	sendMu.Lock()
	cancel := sendCancel
	sendCancel = nil
	sendMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// -------------------------------------------------------------- receiving

type waitingFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
}

type waitingReply struct {
	Files []waitingFile `json:"files"`
	Dir   string        `json:"dir"`
	Error string        `json:"error"`
}

// GetWaitingFiles lists files received by Taildrop that the app has not
// consumed yet. They live in the account's tsnet directory, so the Dir field
// lets the UI discard results that belong to a previous account.
func GetWaitingFiles() string {
	srv := currentServer()
	if srv == nil {
		out, _ := json.Marshal(waitingReply{Files: []waitingFile{}})
		return string(out)
	}
	lc, err := srv.LocalClient()
	if err != nil {
		out, _ := json.Marshal(waitingReply{Files: []waitingFile{}, Error: err.Error()})
		return string(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	files, err := lc.WaitingFiles(ctx)
	if err != nil {
		out, _ := json.Marshal(waitingReply{Files: []waitingFile{}, Error: friendlyTaildropError(err)})
		return string(out)
	}
	list := make([]waitingFile, 0, len(files))
	for _, f := range files {
		list = append(list, waitingFile{Name: f.Name, Size: f.Size})
	}
	out, _ := json.Marshal(waitingReply{Files: list, Dir: srv.Dir})
	return string(out)
}

// SaveWaitingFile streams one staged Taildrop file into destPath (normally in
// the app cache). Kotlin then publishes that copy to Download/SAF and calls
// DeleteWaitingFile. Returns "" on success or an "ERROR: ..." string.
func SaveWaitingFile(name, destPath string) string {
	base := filepath.Base(name)
	if base != name || base == "." || base == ".." || strings.TrimSpace(base) == "" {
		return "ERROR: 非法文件名"
	}
	srv := currentServer()
	if srv == nil {
		return "ERROR: 未启动"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "ERROR: " + err.Error()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	rc, _, err := lc.GetWaitingFile(ctx, base)
	if err != nil {
		return "ERROR: " + friendlyTaildropError(err)
	}
	defer rc.Close()

	if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
		return "ERROR: " + err.Error()
	}
	f, err := os.OpenFile(destPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0644)
	if err != nil {
		return "ERROR: " + err.Error()
	}
	if _, err := io.Copy(f, rc); err != nil {
		f.Close()
		os.Remove(destPath)
		return "ERROR: " + err.Error()
	}
	if err := f.Close(); err != nil {
		os.Remove(destPath)
		return "ERROR: " + err.Error()
	}
	return ""
}

// DeleteWaitingFile removes the staging copy after it has been published.
func DeleteWaitingFile(name string) string {
	base := filepath.Base(name)
	if base != name {
		return "ERROR: 非法文件名"
	}
	srv := currentServer()
	if srv == nil {
		return "ERROR: 未启动"
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return "ERROR: " + err.Error()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := lc.DeleteWaitingFile(ctx, base); err != nil {
		return "ERROR: " + friendlyTaildropError(err)
	}
	return ""
}
