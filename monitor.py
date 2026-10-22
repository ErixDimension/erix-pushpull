#!/usr/bin/env python3
import subprocess, time, json, sys, os, re
from datetime import datetime

RUN_ID = sys.argv[1] if len(sys.argv) > 1 else None
REPO = None
WORKDIR = os.path.expanduser("~/erix-gh")
SPIN = ["⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"]
ANSI = re.compile(r'\x1b\[[0-9;]*m')

R="\033[0m"; B="\033[1m"; D="\033[2m"
RED="\033[1;31m"; GRN="\033[1;32m"; YEL="\033[1;33m"
BLU="\033[1;34m"; CYN="\033[1;36m"; WHT="\033[1;37m"

STEP_TIME = {
    "Set up job": 5, "Checkout code": 3,
    "Set up Python 3.11": 15,
    "Install system dependencies": 90,
    "Install buildozer": 45,
    "Cache buildozer global directory": 45,
    "Cache buildozer project directory": 45,
    "Build APK with buildozer": 2100,
    "Upload APK artifact": 45,
    "Upload build log (on failure)": 10,
    "Complete job": 5,
}

SUB = [
    (0,    "Persiapan buildozer",   "Building target environment"),
    (20,   "Download SDK",          "commandlinetools-linux (~150 MB)"),
    (240,  "Extract SDK",           "Extracting commandlinetools"),
    (300,  "Install SDK packages",  "platform-tools, build-tools-33, android-33"),
    (450,  "Download NDK r25b",     "android-ndk-r25b-linux.zip (~950 MB)"),
    (1400, "Extract NDK",           "Extracting android-ndk-r25b"),
    (1500, "Build Python (host)",   "python3.11 for host"),
    (1800, "Build Python (target)", "python3.11 for arm64-v8a"),
    (2400, "Build libraries",       "sdl2, libffi, openssl, sqlite3"),
    (2700, "Build Kivy ⚠️",         "Cythonizing kivy — FASE KRITIS 10-15 min"),
    (3300, "Build pyjnius",         "pyjnius for arm64-v8a"),
    (3500, "Build android module",  "android for arm64-v8a"),
    (3650, "Package APK",           "Packaging + signing APK"),
    (3750, "Copy to bin/",          "Android packaging done!"),
]

def vlen(s): return len(ANSI.sub('', s))

def detect_repo():
    global REPO
    try:
        r = subprocess.run(["gh","repo","view","--json","nameWithOwner","-q",".nameWithOwner"],
                            capture_output=True, text=True, timeout=10, cwd=WORKDIR)
        if r.returncode == 0:
            REPO = r.stdout.strip(); return True
    except Exception: pass
    return False

def api_get(p):
    try:
        r = subprocess.run(["gh","api",p], capture_output=True, text=True, timeout=20)
        if r.returncode == 0: return json.loads(r.stdout)
    except Exception: pass
    return None

def get_job():
    d = api_get(f"/repos/{REPO}/actions/runs/{RUN_ID}/jobs")
    if d:
        js = d.get("jobs",[])
        if js: return js[0]
    return None

def get_log():
    try:
        r = subprocess.run(["gh","run","view",str(RUN_ID),"--log"],
                            capture_output=True, text=True, timeout=60)
        if r.returncode == 0: return r.stdout
    except Exception: pass
    return ""

def pdt(s):
    if not s: return None
    try: return datetime.fromisoformat(s.replace("Z","+00:00"))
    except Exception: return None

def fmt(s):
    if s is None or s < 0: return "--:--"
    s = int(s)
    if s > 86400: return "--:--"
    h,m,x = s//3600, (s%3600)//60, s%60
    return f"{h:02d}:{m:02d}:{x:02d}" if h else f"{m:02d}:{x:02d}"

def pbar(pct, w=40):
    pct = max(0, min(100, pct))
    f = int(w*pct/100); e = w-f
    c = GRN if pct>=70 else CYN if pct>=40 else YEL
    return f"{c}{'█'*f}{D}{'░'*e}{R}"

def pad(s, w):
    v = vlen(s)
    if v >= w:
        clean = ANSI.sub('', s)
        return clean[:max(1,w-1)] + "…"
    return s + " "*(w-v)

def box(title, lines, color=CYN, width=64):
    inner = width - 4
    o = [f"  {color}╭─ {B}{title}{R}{color} " + "─"*(width - vlen(title) - 5) + f"╮{R}"]
    for ln in lines:
        o.append(f"  {color}│{R} {pad(ln, inner)} {color}│{R}")
    o.append(f"  {color}╰" + "─"*(width-1) + f"╯{R}")
    return o

def compute(job):
    el = 0; exp = 0
    for st in job.get("steps",[]):
        name = st.get("name",""); status = st.get("status","")
        s = pdt(st.get("startedAt")); c = pdt(st.get("completedAt"))
        if status == "completed" and s and c:
            d = (c-s).total_seconds(); el += d; exp += d
        elif status == "in_progress" and s:
            now = datetime.now(s.tzinfo)
            d = (now-s).total_seconds()
            est = max(d*1.4, STEP_TIME.get(name, 30))
            el += d; exp += est
        else:
            exp += STEP_TIME.get(name, 30)
    pct = (el/exp*100) if exp>0 else 0
    return pct, max(0, exp-el)

def detect_sub(job):
    for st in job.get("steps",[]):
        if st.get("name")=="Build APK with buildozer" and st.get("status")=="in_progress":
            s = pdt(st.get("startedAt"))
            if not s: return None
            now = datetime.now(s.tzinfo)
            el = (now-s).total_seconds()
            cur = SUB[0]; nxt = SUB[1] if len(SUB)>1 else None
            for i,(t,lbl,det) in enumerate(SUB):
                if el >= t:
                    cur = (t,lbl,det)
                    nxt = SUB[i+1] if i+1 < len(SUB) else None
                else: break
            return (cur,nxt)
    return None

def step_lines(job, spin):
    out = []
    for st in job.get("steps",[]):
        n = st.get("name",""); status = st.get("status",""); c = st.get("conclusion","")
        s = pdt(st.get("startedAt")); e = pdt(st.get("completedAt"))
        if c == "success":
            d = fmt((e-s).total_seconds()) if s and e else ""
            out.append(f"{GRN}✓{R} {n:<42}{D}({d}){R}")
        elif c in ("failure","cancelled"):
            out.append(f"{RED}✗{R} {RED}{n:<42}{R}{RED}FAILED{R}")
        elif status == "in_progress":
            d = ""
            if s:
                d = fmt((datetime.now(s.tzinfo)-s).total_seconds())
            out.append(f"{YEL}{spin}{R} {B}{n:<42}{R}{YEL}({d}){R}")
        else:
            out.append(f"{D}○ {n}{R}")
    return out

def render(run_id, repo, job, si):
    L = [""]
    spin = SPIN[si % len(SPIN)]
    if not job:
        L.append(f"  {YEL}Menunggu data job...{R}")
        sys.stdout.write("\033[2J\033[H" + "\n".join(L) + "\n")
        sys.stdout.flush()
        return
    status = job.get("status","unknown")
    concl = job.get("conclusion","")
    s = pdt(job.get("startedAt"))
    el = (datetime.now(s.tzinfo)-s).total_seconds() if s else 0
    pct, rem = compute(job)
    sub = detect_sub(job)

    sc = YEL if status=="in_progress" else (GRN if concl=="success" else RED)
    sic = spin if status=="in_progress" else ("✓" if concl=="success" else "✗")

    L.append(f"  {CYN}{B}╔════════════════════════════════════════════════════════════════╗{R}")
    L.append(f"  {CYN}{B}║        E R I X   B U I L D   M O N I T O R   ·  L I V E       ║{R}")
    L.append(f"  {CYN}{B}╚════════════════════════════════════════════════════════════════╝{R}")
    L.append("")
    L.append(f"  {WHT}Run ID    :{R} {run_id}")
    L.append(f"  {WHT}Repo      :{R} {repo}")
    L.append(f"  {WHT}Status    :{R} {sc}{sic} {status.upper()}{R}")
    L.append(f"  {WHT}Elapsed   :{R} {B}{WHT}{fmt(el)}{R}")
    L.append(f"  {WHT}ETA       :{R} {YEL}{fmt(rem)}{R}")
    L.append("")
    L += box("STEPS", step_lines(job, spin), CYN)
    L.append("")
    p = [f"{pbar(pct,40)}  {B}{WHT}{pct:5.1f}%{R}"]
    if sub:
        cur, nxt = sub
        p.append("")
        p.append(f"{CYN}Fase saat ini :{R} {B}{YEL}{cur[1]}{R}")
        p.append(f"{CYN}Detail        :{R} {WHT}{cur[2]}{R}")
        if nxt:
            p.append(f"{D}Berikutnya    : {nxt[1]}{R}")
    L += box("PROGRESS", p, GRN if pct>70 else YEL if pct>40 else CYN)
    L.append("")
    if sub and status=="in_progress":
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
    L.append(f"  {D}github.com/{repo}/actions/runs/{run_id}{R}")
    L.append("")
    sys.stdout.write("\033[2J\033[H" + "\n".join(L))
    sys.stdout.flush()

def render_final(run_id, repo, job, log):
    L = [""]
    concl = job.get("conclusion","unknown") if job else "unknown"
    s = pdt(job.get("startedAt")) if job else None
    el = (datetime.now(s.tzinfo)-s).total_seconds() if s else 0
    ok = concl == "success"
    bd = GRN if ok else RED

    L.append(f"  {bd}{B}╔════════════════════════════════════════════════════════════════╗{R}")
    if ok:
        L.append(f"  {bd}{B}║              ✓   B U I L D   S U K S E S                       ║{R}")
    else:
        L.append(f"  {bd}{B}║              ✗   B U I L D   G A G A L                         ║{R}")
    L.append(f"  {bd}{B}╚════════════════════════════════════════════════════════════════╝{R}")
    L.append("")
    L.append(f"  {WHT}Run ID    :{R} {run_id}")
    L.append(f"  {WHT}Result    :{R} {bd}{concl}{R}")
    L.append(f"  {WHT}Duration  :{R} {fmt(el)}")
    L.append("")
    L.append(f"  {pbar(100 if ok else 0, 50)}  {B}{WHT}{'100.0' if ok else '0.0'}%{R}")
    L.append("")
    if job:
        L += box("STEPS", step_lines(job," "), CYN)
        L.append("")
    if log:
        lines = [l for l in log.split("\n") if l.strip()][-25:]
        cl = []
        for l in lines:
            t = l.strip()
            if len(t) > 88: t = t[:85]+"..."
            low = t.lower()
            if "error" in low or "failed" in low or "exception" in low:
                cl.append(f"{RED}{t}{R}")
            elif "success" in low or "done" in low or "completed" in low:
                cl.append(f"{GRN}{t}{R}")
            elif "warning" in low or "warn" in low:
                cl.append(f"{YEL}{t}{R}")
            else:
                cl.append(f"{D}{t}{R}")
        if cl:
            L += box("LOG TAIL (baris terakhir sebenarnya)", cl, D)
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
    if not detect_repo():
        print(f"{RED}Gagal detect repo. Cek {WORKDIR}{R}")
        return 1
    if not RUN_ID:
        d = api_get(f"/repos/{REPO}/actions/runs?per_page=1")
        if d and d.get("workflow_runs"):
            RUN_ID = str(d["workflow_runs"][0]["id"])
        else:
            print(f"{RED}Tidak ada run ditemukan{R}")
            return 1
    print(f"{CYN}Monitor: {REPO} · run {RUN_ID}{R}")
    print(f"{D}Initializing...{R}")
    time.sleep(0.5)
    si = 0; last_poll = 0; job = None
    while True:
        now = time.time()
        if now - last_poll > 3:
            last_poll = now
            job = get_job()
            if job and job.get("status") == "completed":
                log = get_log()
                render_final(RUN_ID, REPO, job, log)
                return 0 if job.get("conclusion")=="success" else 1
        si += 1
        render(RUN_ID, REPO, job, si)
        time.sleep(0.25)

if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print(f"\n  {D}Monitor dihentikan. Build tetap jalan di cloud.{R}")
        print(f"  {D}Jalankan lagi: python3 ~/erix-gh/monitor.py{R}\n")
        sys.exit(0)
