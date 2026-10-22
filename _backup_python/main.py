#!/usr/bin/env python3
# ERIX Push Pull - Kivy Android
import os
import re
import shlex
import shutil
import subprocess
import threading
import time

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.progressbar import ProgressBar
from kivy.uix.scrollview import ScrollView
from kivy.uix.tabbedpanel import TabbedPanel, TabbedPanelItem
from kivy.uix.textinput import TextInput


# ============================================================
# PATHS & ENVIRONMENT
# ============================================================
APP_DIR = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR = os.path.join(APP_DIR, "assets")
RUNTIME_DIR = os.environ.get("HOME") or "/data/data/com.erix.erixpushpull/files"
ADB_PATH = os.path.join(RUNTIME_DIR, "adb")
LIB_DIR = os.path.join(RUNTIME_DIR, "lib")

DEFAULT_LOCAL = "/sdcard"
DEFAULT_REMOTE = "/sdcard/"

_SU_CACHE = {"val": None}


def _has_su():
    if _SU_CACHE["val"] is not None:
        return _SU_CACHE["val"]
    try:
        r = subprocess.run(["su", "-c", "id"],
                           capture_output=True, text=True, timeout=5)
        _SU_CACHE["val"] = "uid=0" in (r.stdout or "")
    except Exception:
        _SU_CACHE["val"] = False
    return _SU_CACHE["val"]


def extract_assets():
    try:
        os.makedirs(LIB_DIR, exist_ok=True)
    except Exception:
        pass

    src = os.path.join(ASSETS_DIR, "adb")
    if os.path.isfile(src) and not os.path.isfile(ADB_PATH):
        try:
            shutil.copy(src, ADB_PATH)
            os.chmod(ADB_PATH, 0o755)
        except Exception as e:
            print("extract adb:", e)

    src_lib = os.path.join(ASSETS_DIR, "lib")
    if os.path.isdir(src_lib):
        for fn in os.listdir(src_lib):
            dst = os.path.join(LIB_DIR, fn)
            if not os.path.exists(dst):
                try:
                    shutil.copy(os.path.join(src_lib, fn), dst)
                    os.chmod(dst, 0o755)
                except Exception:
                    pass


def _env_prefix():
    return " ".join([
        "HOME=" + shlex.quote(RUNTIME_DIR),
        "LD_LIBRARY_PATH=" + shlex.quote(LIB_DIR),
        "ANDROID_ADB_SERVER_PORT=5037",
        "ADB_LIBUSB=0",
    ])


def adb_env():
    env = os.environ.copy()
    env["HOME"] = RUNTIME_DIR
    env["LD_LIBRARY_PATH"] = LIB_DIR + ":" + env.get("LD_LIBRARY_PATH", "")
    env["ANDROID_ADB_SERVER_PORT"] = "5037"
    env["ADB_LIBUSB"] = "0"
    return env


def adb_run(args, timeout=30):
    if not os.path.isfile(ADB_PATH):
        return (1, "", "adb not found")

    if _has_su():
        inner = " ".join(shlex.quote(a) for a in [ADB_PATH] + list(args))
        cmd = ["su", "-c", _env_prefix() + " " + inner]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True,
                               timeout=timeout)
            return (r.returncode, r.stdout or "", r.stderr or "")
        except subprocess.TimeoutExpired:
            return (124, "", "TIMEOUT")
        except Exception:
            pass

    try:
        r = subprocess.run([ADB_PATH] + list(args),
                           capture_output=True, text=True,
                           timeout=timeout, env=adb_env())
        return (r.returncode, r.stdout or "", r.stderr or "")
    except subprocess.TimeoutExpired:
        return (124, "", "TIMEOUT")
    except Exception as e:
        return (1, "", repr(e))


def adb_popen(args, **kwargs):
    if _has_su():
        inner = " ".join(shlex.quote(a) for a in [ADB_PATH] + list(args))
        cmd = ["su", "-c", _env_prefix() + " " + inner]
        return subprocess.Popen(cmd, **kwargs)
    return subprocess.Popen([ADB_PATH] + list(args),
                            env=adb_env(), **kwargs)


def adb_devices():
    _rc, out, _err = adb_run(["devices", "-l"], timeout=8)
    devs = []
    for line in (out or "").splitlines():
        line = line.strip()
        if not line or line.startswith("List"):
            continue
        p = line.split()
        if len(p) >= 2 and p[1] in ("device", "recovery"):
            devs.append({"serial": p[0], "state": p[1]})
    return devs


def format_size(n):
    try:
        n = float(n or 0)
    except Exception:
        return "0 B"
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return ("%.1f %s" % (n, u)) if u != "B" else ("%d B" % int(n))
        n /= 1024.0
    return "%.1f PB" % n


def remote_list(serial, path):
    rc, out, err = adb_run(["-s", serial, "shell",
                             "ls -la " + shlex.quote(path)], timeout=10)
    if rc != 0:
        return None
    items = []
    for line in (out or "").splitlines():
        p = line.split(None, 7)
        if len(p) < 8:
            continue
        perms = p[0]
        if not (perms.startswith("-") or perms.startswith("d")
                or perms.startswith("l")):
            continue
        is_dir = perms.startswith("d")
        try:
            size = int(p[4]) if not is_dir else 0
        except ValueError:
            size = 0
        name = p[7]
        if name in (".", ".."):
            continue
        if " -> " in name:
            name = name.split(" -> ")[0]
        full = path.rstrip("/") + "/" + name
        items.append({"name": name, "is_dir": is_dir,
                       "size": size, "path": full})
    items.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))
    return items


def local_size(path):
    try:
        if os.path.isfile(path):
            return os.path.getsize(path)
        total = 0
        for r, d, fs in os.walk(path):
            for f in fs:
                try:
                    total += os.path.getsize(os.path.join(r, f))
                except OSError:
                    pass
        return total
    except Exception:
        return 0


# ============================================================
# DEVICE STATUS BAR
# ============================================================
class DeviceStatus(BoxLayout):
    def __init__(self, **kw):
        super().__init__(orientation="horizontal", size_hint_y=None,
                         height=dp(44), padding=dp(6), spacing=dp(6), **kw)
        self.lbl = Label(text="Device: scanning...",
                         size_hint_x=0.78)
        self.add_widget(self.lbl)
        self.btn = Button(text="⟳", size_hint_x=0.22,
                          background_color=(0.3, 0.5, 0.8, 1))
        self.btn.bind(on_release=lambda *_: self.refresh())
        self.add_widget(self.btn)
        Clock.schedule_interval(lambda dt: self.refresh(), 4.0)

    def refresh(self):
        def worker():
            devs = adb_devices()
            Clock.schedule_once(lambda dt: self._update(devs), 0)
        threading.Thread(target=worker, daemon=True).start()

    @mainthread
    def _update(self, devs):
        if devs:
            d = devs[0]
            self.lbl.text = "🟢 %s (%s)" % (d["serial"], d["state"])
        else:
            self.lbl.text = "🔴 Device tidak terdeteksi"


# ============================================================
# TRANSFER POPUP + PROGRESS
# ============================================================
class TransferPopup(Popup):
    def __init__(self, action, serial, src, dst, **kw):
        super().__init__(**kw)
        self.action = action
        self.serial = serial
        self.src = src
        self.dst = dst
        self.title = "📤 PUSH" if action == "push" else "📥 PULL"
        self.size_hint = (0.95, 0.5)
        self.auto_dismiss = False
        self.cancelled = False
        self._proc = None
        self._t0 = None

        box = BoxLayout(orientation="vertical",
                        padding=dp(12), spacing=dp(8))
        name = os.path.basename(str(src).rstrip("/")) or src
        self.lbl_file = Label(text=name, size_hint_y=None,
                              height=dp(28), bold=True)
        self.lbl_status = Label(text="Memulai...",
                                size_hint_y=None, height=dp(24))
        self.progress = ProgressBar(max=100, value=0,
                                    size_hint_y=None, height=dp(26))
        self.lbl_details = Label(text="", size_hint_y=None,
                                 height=dp(24))
        self.btn_cancel = Button(text="Batal", size_hint_y=None,
                                 height=dp(44),
                                 background_color=(0.8, 0.2, 0.2, 1))
        self.btn_cancel.bind(on_release=self.on_cancel)
        box.add_widget(self.lbl_file)
        box.add_widget(self.lbl_status)
        box.add_widget(self.progress)
        box.add_widget(self.lbl_details)
        box.add_widget(self.btn_cancel)
        self.content = box

    def start(self):
        self.open()
        self._t0 = time.monotonic()
        threading.Thread(target=self._worker, daemon=True).start()

    def on_cancel(self, *_):
        if self.btn_cancel.text == "Tutup":
            self.dismiss()
            return
        self.cancelled = True
        if self._proc:
            try:
                self._proc.kill()
            except Exception:
                pass

    def _worker(self):
        try:
            self._proc = adb_popen(
                ["-s", self.serial, self.action,
                 "-p", self.src, self.dst],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, bufsize=1)
        except Exception as e:
            Clock.schedule_once(lambda dt: self._finish(
                False, "Exec: %r" % e), 0)
            return

        pct_re = re.compile(r"\[\s*(\d+)%\]")
        try:
            for line in self._proc.stdout:
                if self.cancelled:
                    break
                m = pct_re.search(line)
                if m:
                    try:
                        pct = int(m.group(1))
                    except Exception:
                        continue
                    el = time.monotonic() - self._t0
                    Clock.schedule_once(
                        lambda dt, p=pct, e=el:
                        self._update_progress(p, e), 0)
        finally:
            try:
                rc = self._proc.wait(timeout=3)
            except Exception:
                rc = 1

        ok = (rc == 0 and not self.cancelled)
        msg = "Dibatalkan" if self.cancelled else (
            "Selesai ✓" if ok else "Gagal (rc=%d)" % rc)
        Clock.schedule_once(lambda dt: self._finish(ok, msg), 0)

    @mainthread
    def _update_progress(self, pct, elapsed):
        self.progress.value = pct
        self.lbl_status.text = "Transfer... %d%%" % pct
        self.lbl_details.text = "Elapsed: %.1fs" % elapsed

    @mainthread
    def _finish(self, ok, msg):
        self.lbl_status.text = ("✓ " if ok else "✗ ") + msg
        if ok:
            self.progress.value = 100
        self.btn_cancel.text = "Tutup"
        self.btn_cancel.background_color = (0.2, 0.7, 0.3, 1)


# ============================================================
# PUSH PANEL (A → B)
# ============================================================
class PushPanel(BoxLayout):
    def __init__(self, **kw):
        super().__init__(orientation="vertical", padding=dp(8),
                         spacing=dp(6), **kw)

        self.add_widget(Label(text="[b]📤 PUSH — Host → Device[/b]",
                              markup=True, size_hint_y=None, height=dp(28)))
        self.add_widget(Label(text="File lokal (A):",
                              size_hint_y=None, height=dp(20)))
        self.local_chooser = FileChooserListView(
            path=DEFAULT_LOCAL, size_hint_y=1)
        self.add_widget(self.local_chooser)

        self.add_widget(Label(text="Tujuan di Device B:",
                              size_hint_y=None, height=dp(20)))
        self.remote_input = TextInput(text=DEFAULT_REMOTE, multiline=False,
                                       size_hint_y=None, height=dp(42))
        self.add_widget(self.remote_input)

        browse = Button(text="📂 Lihat remote",
                        size_hint_y=None, height=dp(36),
                        background_color=(0.3, 0.5, 0.8, 1))
        browse.bind(on_release=lambda *_: self._browse())
        self.add_widget(browse)

        start = Button(text="⬆ PUSH SEKARANG", size_hint_y=None,
                       height=dp(52),
                       background_color=(0.15, 0.65, 0.15, 1))
        start.bind(on_release=lambda *_: self._do_push())
        self.add_widget(start)

        self.lbl = Label(text="", size_hint_y=None, height=dp(22))
        self.add_widget(self.lbl)

    def _browse(self):
        devs = adb_devices()
        if not devs:
            self.lbl.text = "No device"
            return
        serial = devs[0]["serial"]
        path = self.remote_input.text.strip() or "/sdcard/"
        items = remote_list(serial, path)
        if items is None:
            self.lbl.text = "Gagal baca " + path
            return
        self._show_browser(serial, path, items)

    def _show_browser(self, serial, path, items):
        content = BoxLayout(orientation="vertical",
                            padding=dp(4), spacing=dp(4))
        scroll = ScrollView()
        lb = BoxLayout(orientation="vertical",
                       size_hint_y=None, spacing=dp(2))
        lb.bind(minimum_height=lb.setter("height"))
        popup = Popup(title="Remote: " + path, content=content,
                      size_hint=(0.95, 0.85))

        parent = os.path.dirname(path.rstrip("/")) or "/"
        pbtn = Button(text="📁 ..", size_hint_y=None, height=dp(36),
                      background_color=(0.4, 0.4, 0.4, 1))
        def go_up(*_):
            self.remote_input.text = parent + "/"
            popup.dismiss()
            self._browse()
        pbtn.bind(on_release=go_up)
        lb.add_widget(pbtn)

        for it in items:
            icon = "📁" if it["is_dir"] else "📄"
            name = it["name"]
            if len(name) > 40:
                name = name[:37] + "..."
            label = "%s %s  %s" % (
                icon, name,
                "" if it["is_dir"] else format_size(it["size"]))
            btn = Button(text=label, size_hint_y=None, height=dp(40),
                         halign="left")
            def make_cb(item=it):
                def cb(*_):
                    self.remote_input.text = item["path"] + (
                        "/" if item["is_dir"] else "")
                    popup.dismiss()
                    if item["is_dir"]:
                        self._browse()
                return cb
            btn.bind(on_release=make_cb())
            lb.add_widget(btn)

        scroll.add_widget(lb)
        content.add_widget(scroll)
        popup.open()

    def _do_push(self):
        sel = self.local_chooser.selection
        if not sel:
            self.lbl.text = "Pilih file/folder dulu"
            return
        src = sel[0]
        if not (os.path.isfile(src) or os.path.isdir(src)):
            self.lbl.text = "Sumber tidak valid"
            return
        dst = self.remote_input.text.strip() or DEFAULT_REMOTE
        devs = adb_devices()
        if not devs:
            self.lbl.text = "Device B tidak terdeteksi"
            return
        serial = devs[0]["serial"]
        size = local_size(src)
        msg = ("PUSH:\n%s\n→ %s\n\nDevice: %s\nUkuran: %s"
               % (os.path.basename(src), dst, serial, format_size(size)))
        self._confirm(msg, lambda: TransferPopup(
            "push", serial, src, dst).start())

    def _confirm(self, msg, on_yes):
        content = BoxLayout(orientation="vertical",
                            padding=dp(8), spacing=dp(6))
        content.add_widget(Label(text=msg))
        btns = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6))
        popup = Popup(title="Konfirmasi", content=content,
                      size_hint=(0.9, 0.5))
        yes = Button(text="YA", background_color=(0.2, 0.8, 0.2, 1))
        no = Button(text="BATAL", background_color=(0.8, 0.2, 0.2, 1))
        yes.bind(on_release=lambda *_: (popup.dismiss(), on_yes()))
        no.bind(on_release=lambda *_: popup.dismiss())
        btns.add_widget(yes)
        btns.add_widget(no)
        content.add_widget(btns)
        popup.open()


# ============================================================
# PULL PANEL (B → A)
# ============================================================
class PullPanel(BoxLayout):
    def __init__(self, **kw):
        super().__init__(orientation="vertical", padding=dp(8),
                         spacing=dp(6), **kw)

        self.add_widget(Label(text="[b]📥 PULL — Device → Host[/b]",
                              markup=True, size_hint_y=None, height=dp(28)))
        self.add_widget(Label(text="Sumber di Device B:",
                              size_hint_y=None, height=dp(20)))
        self.remote_input = TextInput(text=DEFAULT_REMOTE, multiline=False,
                                       size_hint_y=None, height=dp(42))
        self.add_widget(self.remote_input)

        browse = Button(text="📂 Lihat remote",
                        size_hint_y=None, height=dp(36),
                        background_color=(0.3, 0.5, 0.8, 1))
        browse.bind(on_release=lambda *_: self._browse())
        self.add_widget(browse)

        self.add_widget(Label(text="Folder lokal tujuan (A):",
                              size_hint_y=None, height=dp(20)))
        self.local_chooser = FileChooserListView(
            path=DEFAULT_LOCAL, dirselect=True, size_hint_y=1)
        self.add_widget(self.local_chooser)

        start = Button(text="⬇ PULL SEKARANG", size_hint_y=None,
                       height=dp(52),
                       background_color=(0.2, 0.55, 0.85, 1))
        start.bind(on_release=lambda *_: self._do_pull())
        self.add_widget(start)

        self.lbl = Label(text="", size_hint_y=None, height=dp(22))
        self.add_widget(self.lbl)

    def _browse(self):
        devs = adb_devices()
        if not devs:
            self.lbl.text = "No device"
            return
        serial = devs[0]["serial"]
        path = self.remote_input.text.strip() or "/sdcard/"
        items = remote_list(serial, path)
        if items is None:
            self.lbl.text = "Gagal baca"
            return
        PushPanel._show_browser(self, serial, path, items)

    def _do_pull(self):
        remote = self.remote_input.text.strip()
        if not remote:
            self.lbl.text = "Isi path remote"
            return
        local_dir = self.local_chooser.path
        if not os.path.isdir(local_dir):
            self.lbl.text = "Folder lokal tidak valid"
            return
        devs = adb_devices()
        if not devs:
            self.lbl.text = "Device tidak terdeteksi"
            return
        serial = devs[0]["serial"]
        msg = ("PULL:\n%s\n→ %s\n\nDevice: %s"
               % (remote, local_dir, serial))
        content = BoxLayout(orientation="vertical",
                            padding=dp(8), spacing=dp(6))
        content.add_widget(Label(text=msg))
        btns = BoxLayout(size_hint_y=None, height=dp(48), spacing=dp(6))
        popup = Popup(title="Konfirmasi", content=content,
                      size_hint=(0.9, 0.5))
        yes = Button(text="YA", background_color=(0.2, 0.8, 0.2, 1))
        no = Button(text="BATAL", background_color=(0.8, 0.2, 0.2, 1))
        yes.bind(on_release=lambda *_: (
            popup.dismiss(),
            TransferPopup("pull", serial, remote, local_dir).start()))
        no.bind(on_release=lambda *_: popup.dismiss())
        btns.add_widget(yes)
        btns.add_widget(no)
        content.add_widget(btns)
        popup.open()


# ============================================================
# MAIN APP
# ============================================================
class MainScreen(BoxLayout):
    def __init__(self, **kw):
        super().__init__(orientation="vertical", **kw)
        self.add_widget(DeviceStatus())
        tp = TabbedPanel(do_default_tab=False)
        t1 = TabbedPanelItem(text="📤 PUSH (A→B)")
        t1.add_widget(PushPanel())
        t2 = TabbedPanelItem(text="📥 PULL (B→A)")
        t2.add_widget(PullPanel())
        tp.add_widget(t1)
        tp.add_widget(t2)
        tp.default_tab = t1
        self.add_widget(tp)


class ErixPushPullApp(App):
    def build(self):
        Window.clearcolor = (0.08, 0.08, 0.12, 1)
        extract_assets()
        threading.Thread(target=self._boot, daemon=True).start()
        return MainScreen()

    def _boot(self):
        try:
            adb_run(["start-server"], timeout=20)
        except Exception:
            pass

    def on_stop(self):
        try:
            adb_run(["kill-server"], timeout=10)
        except Exception:
            pass


if __name__ == "__main__":
    ErixPushPullApp().run()
