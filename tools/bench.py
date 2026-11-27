"""
ScanPesa accuracy benchmark.

Runs the app's real photo reader on every image in the phone's bench folder (over USB),
then scores the automatic result against samples/answers.csv.

    python tools/bench.py            # run on the phone and score
    python tools/bench.py --score    # only re-score the last results file
    python tools/bench.py -v         # also print the OCR text of failures

Setup (once per set of photos): see README "Accuracy benchmark".
"""
import csv, json, os, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"
# --set tilt  -> samples/answers_tilt.csv, phone folder files/tilt
SET = sys.argv[sys.argv.index("--set") + 1] if "--set" in sys.argv else "bench"
RESULTS = os.path.join(ROOT, "samples", f"{SET}_results.jsonl")
ANSWERS = os.path.join(ROOT, "samples", "answers.csv" if SET == "bench" else f"answers_{SET}.csv")
PKG = "com.scanpesa.app"


def adb(*args, **kw):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def run_on_phone():
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.automation.AdbBenchActivity", "--es", "set", SET)
    t0 = time.time()
    while time.time() - t0 < 600:
        time.sleep(2)
        if "benchmark: DONE" in adb("logcat", "-d", "-s", "ScanPesaBench:I").stdout:
            break
    else:
        sys.exit("benchmark did not finish in 10 minutes")
    out = adb("exec-out", "run-as", PKG, "cat", f"files/{SET}_results.jsonl")
    with open(RESULTS, "w", encoding="utf-8") as f:
        f.write(out.stdout)


def score(verbose=False):
    answers = {r["file"]: r for r in csv.DictReader(open(ANSWERS, encoding="utf-8-sig"))}
    results = {}
    for line in open(RESULTS, encoding="utf-8"):
        if line.strip():
            o = json.loads(line)
            results[o["file"]] = o
    ok = total = 0
    rows = []
    for name, a in answers.items():
        if a["type"] == "SKIP":
            continue
        total += 1
        r = results.get(name, {})
        got = (r.get("type", ""), r.get("number", ""), r.get("account", "") or "")
        want = (a["type"], a["number"], a["account"] or "")
        # account comparison ignores case and separators
        norm = lambda s: "".join(ch for ch in s.upper() if ch.isalnum())
        good = got[0] == want[0] and got[1] == want[1] and norm(got[2]) == norm(want[2])
        ok += good
        rows.append((good, name, want, got, r))
    # A Paybill without an account keeps Go disabled in the app, so it's a safe miss too.
    blocked = lambda got: not got[1] or (got[0] == "PAYBILL" and not got[2])
    safe = sum(1 for g, n, w, got, r in rows if not g and blocked(got))
    wrong = sum(1 for g, n, w, got, r in rows if not g and not blocked(got))
    for good, name, want, got, r in sorted(rows, key=lambda x: (x[0], not blocked(x[3]))):
        mark = "OK   " if good else ("SAFE " if blocked(got) else "WRONG")
        print(f"{mark} {name[:34]:34} want {want[0]:10} {want[1]:12} {want[2]:15} got {got[0]:10} {got[1]:12} {got[2]}")
        if verbose and not good:
            print("       votes:", r.get("votes", ""), "unsure:", r.get("uncertain", ""), "candidates:", r.get("candidates", ""))
            print("       ocr:", (r.get("text", "") or "").replace("\n", " | ")[:300])
    ms = [r.get("ms", 0) for r in results.values()]
    print(f"\nSCORE {ok}/{total} correct ({100 * ok / max(total, 1):.0f}%)  |  safe misses: {total - ok - wrong}"
          f"  |  WRONG: {wrong}  |  avg {sum(ms) / max(len(ms), 1):.0f} ms/photo")


if __name__ == "__main__":
    if "--score" not in sys.argv:
        run_on_phone()
    score(verbose="-v" in sys.argv)
