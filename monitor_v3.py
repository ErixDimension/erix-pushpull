#!/usr/bin/env python3
import subprocess, time, json, sys, os, re
from datetime import datetime, timezone

WORKDIR = os.path.expanduser("~/erix-gh")
RUN_ID = sys.argv[1] if len(sys.argv) > 1 else None
SPIN = ["⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"]
ANSI = re.compile(r'\x1b\[[0-9;]*m')

R="\033[0m"; B="\033[1m"; D="\033[2m"
RED="\033[1;31m"; GRN="\033[1;32m"; YEL="\033[1;33m"
BLU="\033[1;34m"; CYN="\033[1;36m"; WHT="\033[1;37m"; MAG="\033[1;35m"

STEP_TIME = {
    "Set up job": 5, "Checkout code": 3,
    "Set up Python 3.11": 15,
    "Install system dependencies": 120,
    "Install buildozer": 60,
    "Cache buildozer global directory": 60,
    "Cache buildozer project directory": 60,
    "Build APK with buildozer": 2400,
    "Upload APK artifact": 60,
    "Complete job": 10,
}

SUB_STAGES = [
    (0,    "Persiapan buildozer",   "Building target environment"),
    (30,   "Download SDK",          "commandlinetools-linux (~150 MB)"),
    (300,  "Extract SDK",           "Extracting commandlinetools"),
    (360,  "Install SDK packages",  "platform-tools, build-tools-33, android-33"),
    (500,  "Download NDK r25b",     "android-ndk-r25b-linux.zip (~950 MB)"),
    (1400, "Extract NDK",           "Extracting android-ndk-r25b"),
    (1500, "Build Python (host)",   "python3.11 for host"),
    (1800, "Build Python (target)", "python3.11 for arm64-v8a"),
    (2400, "Build libraries",       "sdl2, libffi, openssl, sqlite3"),
    (2700, "Build Kivy ⚠️",         "Cythonizing kivy — FASE KRITIS 10-15 menit"),
    (3300, "Build pyjnius",         "pyjnius for arm64-v8a"),
    (3500, "Build android module",  "android for arm64-v8a"),
    (3650, "Package APK",           "Packaging + signing APK"),
    (3750, "Copy to bin/",          "Android packaging done!"),
]

def vlen(s): return len(ANSI.sub('', s))
def fmt(s):
    if s is None or s < 0: return "--:--"
    s = int(s)
    if s > 86400: return "--:--"
    h, m, x = s//3600, (s%3600)//60, s%60
    return f"{h:02d}:{m:02d}:{x:02d}" if h else f"{m:02d}:{x:02d}"

def pbar(pct, w=36):
    pct = max(0, min(100, pct))
    f = int(w * pct / 100); e = w - f
    c = GRN if pct >= 70 else CYN if pct >= 40 else YEL
    return f"{c}{'█'*f}{D}{'░'*e}{R}"

def pad(s, w):
    v = vlen(s)
    if v >= w:
        clean = ANSI.sub('', s)
        return clean[:max(1, w-1)] + "…"
    return s + " " * (w - v)

def box(title, lines, color=CYN, width=66):
    inner = width - 4
    o = [f"  {color}╭─ {B}{title}{R}{color} " + "─" * (width - vlen(title) - 5) + f"╮{R}"]
    for ln in lines:
        o.append(f"  {color}│{R} {pad(ln, inner)} {color}│{R}")
    o.append(f"  {color}╰" + "─" * (width - 1) + f"╯{R}")
    return o

def api_get(path):
    try:
        r = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=20)
        if r.returncode == 0:
            return json.loads(r.stdout)
    except Exception: pass
    return None

def get_job(run_id):
    d = api_get(f"/repos/ErixDimension/erix-pushpull/actions/runs/{run_id}/jobs")
    if d and d.get("jobs"):
        return d["jobs"][0]
    return None

def pdt(s):
    if not s: return None
    try: return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except Exception: return None

def compute_progress(job):
    if not job: return 0, 0, 0
    steps = job.get("steps", [])
    total_exp = 0; elapsed = 0
    for st in steps:
        name = st.get("name", "")
        status = st.get("status", "")
        s = pdt(st.get("started_at")); c = pdt(st.get("completed_at"))
        w = STEP_TIME.get(name, 30)
        total_exp += w
        if status == "completed":
            if s and c:
                elapsed += (c - s).total_seconds()
            else:
                elapsed += w
        elif status == "in_progress":
            if s:
                now = datetime.now(s.tzinfo)
                d = (now - s).total_seconds()
                elapsed += d
                # Beri estimasi sisa untuk step ini
                total_exp = total_exp - w + max(d, w * 0.8)
            else:
                elapsed += 0
                total_exp = total_exp - w + w * 0.5
    pct = (elapsed / total_exp * 100) if total_exp > 0 else 0
    return min(pct, 99.5), elapsed, max(0, total_exp - elapsed)

def detect_sub_stage(job):
    for st in job.get("steps", []):
        if st.get("name") == "Build APK with buildozer" and st.get("status") == "in_progress":
            s = pdt(st.get("started_at"))
            if not s: return None
            el = (datetime.now(s.tzinfo) - s).total_seconds()
            cur = SUB_STAGES[0]
            nxt = SUB_STAGES[1] if len(SUB_STAGES) > 1 else None
            for i, (t, lbl, det) in enumerate(SUB_STAGES):
                if el >= t:
                    cur = (t, lbl, det)
                    nxt = SUB_STAGES[i+1] if i+1 < len(SUB_STAGES) else None
                else:
                    break
            return (cur, nxt)
    return None

def render(run_id, job, si):
    L = [""]
    spin = SPIN[si % len(SPIN)]
    if not job:
        L.append(f"  {YEL}Menunggu data job...{R}")
        sys.stdout.write("\033[2J\033[H" + "\n".join(L) + "\n")
        sys.stdout.flush()
        return
    status = job.get("status", "unknown")
    concl = job.get("conclusion", "")
    s = pdt(job.get("started_at"))
    el_total = (datetime.now(s.tzinfo) - s).total_seconds() if s else 0
    pct, elapsed, rem = compute_progress(job)
    sub = detect_sub_stage(job)

    sc = YEL if status == "in_progress" else (GRN if concl == "success" else RED)
    sic = spin if status == "in_progress" else ("✓" if concl == "success" else "✗")

    L.append(f"  {CYN}{B}╔══════════════════════════════════════════════════════════════════╗{R}")
    L.append(f"  {CYN}{B}║        E R I X   B U I L D   M O N I T O R   ·  L I V E   V 3     ║{R}")
    L.append(f"  {CYN}{B}╚══════════════════════════════════════════════════════════════════╝{R}")
    L.append("")
    L.append(f"  {WHT}Run ID    :{R} {run_id}")
    L.append(f"  {WHT}Status    :{R} {sc}{sic} {status.upper()}{R}")
    L.append(f"  {WHT}Total     :{R} {B}{WHT}{fmt(el_total)}{R}")
    L.append(f"  {WHT}ETA       :{R} {YEL}{fmt(rem)}{R}")
    L.append("")

    step_lines = []
    for st in job.get("steps", []):
        n = st.get("name", ""); status = st.get("status", ""); c = st.get("conclusion", "")
        s = pdt(st.get("started_at")); e = pdt(st.get("completed_at"))
        if c == "success":
            d = fmt((e - s).total_seconds()) if s and e else ""
            step_lines.append(f"{GRN}✓{R} {n:<44}{D}({d}){R}")
        elif c in ("failure", "cancelled"):
            step_lines.append(f"{RED}✗{R} {RED}{n:<44}FAILED{R}")
        elif status == "in_progress":
            d = fmt((datetime.now(s.tzinfo) - s).total_seconds()) if s else ""
            step_lines.append(f"{YEL}{spin}{R} {B}{n:<44}{R}{YEL}({d}){R}")
        else:
            step_lines.append(f"{D}○ {n}{R}")
    L += box("STEPS", step_lines, CYN)
    L.append("")

    prog = [f"{pbar(pct, 40)}  {B}{WHT}{pct:5.1f}%{R}"]
    if sub:
        cur, nxt = sub
        prog.append("")
        prog.append(f"{CYN}Fase saat ini :{R} {B}{YEL}{cur[1]}{R}")
        prog.append(f"{CYN}Detail        :{R} {WHT}{cur[2]}{R}")
        if nxt:
            prog.append(f"{D}Berikutnya    : {nxt[1]}{R}")
    L += box("PROGRESS", prog, GRN if pct > 70 else YEL if pct > 40 else CYN)
    L.append("")

    if sub and status == "in_progress":
        cur, nxt = sub
        lg = [f"{D}[INFO]{R}    {cur[2]}", ""]
        low = cur[1].lower()
        if "kivy" in low:
            lg.append(f"{RED}⚠  FASE KRITIS — 10-15 menit tanpa progress{R}")
            lg.append(f"{D}   Jangan cancel, ini normal{R}")
        elif "ndk" in low:
            lg.append(f"{YEL}⚠  Download besar (~950 MB) — 8-15 menit{R}")
        elif "sdk" in low:
            lg.append(f"{YEL}⚠  Download SDK (~150 MB) — 2-5 menit{R}")
        if nxt:
            lg.append("")
            lg.append(f"{D}Setelah ini → {nxt[1]}{R}")
        L += box("LOG AKTIF (prediksi dari fase)", lg, BLU)
        L.append("")

    L.append(f"  {D}Ctrl+C = stop monitor (build tetap jalan di cloud){R}")
    L.append(f"  {D}github.com/ErixDimension/erix-pushpull/actions/runs/{run_id}{R}")
    L.append("")
    sys.stdout.write("\033[2J\033[H" + "\n".join(L))
    sys.stdout.flush()

def render_final(run_id, job):
    L = [""]
    concl = job.get("conclusion", "unknown") if job else "unknown"
    s = pdt(job.get("started_at")) if job else None
    el = (datetime.now(s.tzinfo) - s).total_seconds() if s else 0
    ok = concl == "success"
    bd = GRN if ok else RED

    L.append(f"  {bd}{B}╔══════════════════════════════════════════════════════════════════╗{R}")
    if ok:
        L.append(f"  {bd}{B}║              ✓   B U I L D   S U K S E S                           ║{R}")
    else:
        L.append(f"  {bd}{B}║              ✗   B U I L D   G A G A L                             ║{R}")
    L.append(f"  {bd}{B}╚══════════════════════════════════════════════════════════════════╝{R}")
    L.append("")
    L.append(f"  {WHT}Run ID    :{R} {run_id}")
    L.append(f"  {WHT}Result    :{R} {bd}{concl}{R}")
    L.append(f"  {WHT}Duration  :{R} {fmt(el)}")
    L.append("")
    L.append(f"  {pbar(100 if ok else 0, 50)}  {B}{WHT}{'100.0' if ok else '0.0'}%{R}")
    L.append("")
    if job:
        step_lines = []
        for st in job.get("steps", []):
            n = st.get("name", ""); c = st.get("conclusion", "")
            if c == "success": step_lines.append(f"{GRN}✓{R} {n}")
            elif c in ("failure", "cancelled"): step_lines.append(f"{RED}✗{R} {RED}{n}{R}")
            else: step_lines.append(f"{D}○ {n}{R}")
        L += box("FINAL STEPS", step_lines, CYN)
        L.append("")
    if ok:
        L.append(f"  {GRN}{B}► DOWNLOAD APK:{R}")
        L.append(f"  {CYN}    gh run download {run_id} -n erix-apk{R}")
        L.append(f"  {CYN}    cp erix-apk/*.apk /sdcard/Download/{R}")
        L.append(f"  {CYN}    termux-open /sdcard/Download/*.apk{R}")
        L.append("")
    else:
        L.append(f"  {RED}{B}► CEK ERROR:{R}")
        L.append(f"  {CYN}    gh run view {run_id} --log-failed{R}")
        L.append(f"  {D}   Kirim 30 baris terakhir ke chat{R}")
        L.append("")
    sys.stdout.write("\033[2J\033[H" + "\n".join(L))
    sys.stdout.flush()

def main():
    global RUN_ID
    if not RUN_ID:
        d = api_get("/repos/ErixDimension/erix-pushpull/actions/runs?per_page=1")
        if d and d.get("workflow_runs"):
            RUN_ID = str(d["workflow_runs"][0]["id"])
        else:
            print(f"{RED}Tidak ada run ditemukan{R}")
            return 1
    print(f"{CYN}Memulai monitor v3 untuk run {RUN_ID}...{R}")
    time.sleep(0.5)
    si = 0; last = 0; job = None
    while True:
        now = time.time()
        if now - last > 1.5: # Poll API setiap 1.5s
            last = now
            job = get_job(RUN_ID)
            if job and job.get("status") == "completed":
                render_final(RUN_ID, job)
                return 0 if job.get("conclusion") == "success" else 1
        si += 1
        render(RUN_ID, job, si)
        time.sleep(0.15) # Animasi cepat (0.15s)

if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print(f"\n  {D}Monitor dihentikan. Build tetap jalan di cloud.{R}")
        print(f"  {D}Jalankan lagi: python3 ~/erix-gh/monitor_v3.py{R}\n")
        sys.exit(0)
