"""Per-category breakdown for a bench set: python tools/bench_by_note.py tilt"""
import json, csv, collections, os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
st = sys.argv[1] if len(sys.argv) > 1 else "tilt"
ans = {r['file']: r for r in csv.DictReader(open(os.path.join(root, 'samples', f'answers_{st}.csv'), encoding='utf-8-sig'))}
res = [json.loads(l) for l in open(os.path.join(root, 'samples', f'{st}_results.jsonl'), encoding='utf-8') if l.strip()]
by = collections.defaultdict(lambda: [0, 0])
for r in res:
    a = ans[r['file']]
    ok = r['type'] == a['type'] and r['number'] == a['number'] and (r['account'] or '').upper() == (a['account'] or '').upper()
    by[a['note']][0] += ok; by[a['note']][1] += 1
for k, (o, t) in sorted(by.items()): print(f"  {k:8} {o}/{t}")
