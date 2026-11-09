#!/usr/bin/env python
"""Summarise decrypted diaglog JSONL (file argument or stdin). Tolerates missing fields."""
import argparse
import json
import sys
from collections import Counter, defaultdict


def pct(n, d):
    return "%.1f%% (%d/%d)" % (100.0 * n / d, n, d) if d else "n/a (0)"


def percentiles(vals):
    if not vals:
        return "n/a"
    v = sorted(vals)
    def p(q):
        return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]
    return "p50=%g p90=%g p99=%g max=%g (n=%d)" % (p(.5), p(.9), p(.99), v[-1], len(v))


def words_of(cands):
    """Candidates are [word, score, kind, source] lists, dicts or bare strings."""
    out = []
    for c in cands or []:
        if isinstance(c, (list, tuple)) and c:
            out.append(c[0])
        elif isinstance(c, dict):
            out.append(c.get("word"))
        elif isinstance(c, str):
            out.append(c)
    return out


def head(t):
    print("\n== %s ==" % t)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file", nargs="?")
    a = ap.parse_args()
    src = open(a.file) if a.file else sys.stdin
    ev = []
    for l in src:
        l = l.strip()
        if l:
            try:
                ev.append(json.loads(l))
            except ValueError:
                pass
    ev = [e for e in ev if isinstance(e, dict)]
    ev.sort(key=lambda e: e.get("t", 0))

    head("Events by type")
    for k, n in Counter(e.get("type", "?") for e in ev).most_common():
        print("  %-16s %d" % (k, n))
    if not ev:
        return

    commits = [e for e in ev if e.get("type") == "commit"]
    reverts = [e for e in ev if e.get("type") == "revert"]
    ac = [c for c in commits if c.get("commitType") == "autocorrect"]
    ac_rev = [r for r in reverts if r.get("kind", "autocorrect") == "autocorrect"]
    head("Autocorrect revert rate")
    print("  overall: %s" % pct(len(ac_rev), len(ac)))
    cl, rl = Counter(), Counter()
    for c in ac:
        cl[len(c.get("typed") or "")] += 1
    for r in ac_rev:
        rl[len(r.get("typed") or "")] += 1
    for n in sorted(cl):
        print("  typed len %2d: %s" % (n, pct(rl[n], cl[n])))
    for n in sorted(set(rl) - set(cl)):
        print("  typed len %2d: %d reverts, no matching commits" % (n, rl[n]))

    head("Retyped word absent from candidates after a revert/backspace")
    absent = total = 0
    last_suggest = {}
    examples = []
    for i, e in enumerate(ev):
        t = e.get("type")
        if t == "suggest" and e.get("typed"):
            last_suggest[e["typed"]] = e
        if t == "revert":
            typed = e.get("typed")
            retyped = None
            for f in ev[i + 1:i + 40]:
                if f.get("t", 0) - e.get("t", 0) > 60000:
                    break
                if f.get("type") == "commit" and f.get("commitType") in ("typed", "manualPick"):
                    retyped = f.get("committed")
                    break
            _check = (typed, retyped)
        elif t == "backspaceWord" and e.get("retyped"):
            _check = (e.get("word"), e["retyped"])
        else:
            continue
        typed, retyped = _check
        s = last_suggest.get(typed)
        if not retyped or not s:
            continue
        total += 1
        if retyped not in words_of(s.get("candidates")):
            absent += 1
            if len(examples) < 10:
                examples.append((typed, retyped))
    print("  %s" % pct(absent, total))
    for t_, r_ in examples:
        print("    %r -> retyped %r" % (t_, r_))

    head("Manual strip picks by rank")
    picks = Counter(e.get("rank") for e in ev if e.get("type") == "pick")
    for k in sorted(picks, key=lambda x: (x is None, x)):
        print("  rank %s: %d" % (k, picks[k]))
    if not picks:
        print("  none")

    head("LM outcome (suggest events)")
    sug = [e for e in ev if e.get("type") == "suggest"]
    oc = Counter(e.get("lm", "unknown") for e in sug)
    for k, n in oc.most_common():
        print("  %-10s %s" % (k, pct(n, len(sug))))
    sk = Counter(e.get("lmSkipReason") for e in sug if e.get("lmSkipReason"))
    if sk:
        print("  lmSkipReason:")
        for k, n in sk.most_common():
            print("    %-24s %d" % (k, n))
    waits = [e["lmWaitMs"] for e in sug if isinstance(e.get("lmWaitMs"), (int, float))]
    if waits:
        print("  lmWaitMs: " + percentiles(waits))

    head("Swipe")
    sw = [e for e in ev if e.get("type") == "swipe"]
    print("  swipes: %d" % len(sw))
    if sw:
        print("  decoder: " + ", ".join("%s=%d" % kv for kv in Counter(e.get("decoder", "?") for e in sw).most_common()))
        nc = []
        rej = known = 0
        for e in sw:
            raw = e.get("raw") or e.get("rawCandidates") or e.get("candidates")
            fil = e.get("filtered") or e.get("filteredCandidates")
            # rawCount is the true count; "raw" is truncated to the top 10 by the logger.
            if isinstance(e.get("rawCount"), int):
                nc.append(e["rawCount"])
            elif isinstance(raw, list):
                nc.append(len(raw))
            fw, rw = words_of(fil), words_of(raw)
            if fw and rw:
                known += 1
                if rw[0] != fw[0]:
                    rej += 1
        if nc:
            print("  candidate count: " + percentiles(nc))
            dist = Counter(nc)
            print("  candidate count distribution: " + ", ".join("%d: %d" % kv for kv in sorted(dist.items())))
            if len(dist) == 1 and len(nc) >= 3:
                k = next(iter(dist))
                tk = [e.get("decoderInfo", {}).get("topK") for e in sw if isinstance(e.get("decoderInfo"), dict)]
                print("  NOTE: every swipe has exactly %d candidates: the decoder is capped (topK=%s in "
                      "SwipeDecoderDictionary), so a missing word cannot be ranked lower, it is absent."
                      % (k, tk[0] if tk and tk[0] is not None else "4 hardcoded"))
        if known:
            print("  top raw word not top after filtering: %s" % pct(rej, known))
        lat = [e["latencyMs"] for e in sw if isinstance(e.get("latencyMs"), (int, float))]
        head("Swipe latency (finger up to composing text set)")
        print("  latencyMs: " + percentiles(lat))
        if len(lat) < len(sw):
            print("  %d of %d swipes have no latency (logged by a build without the fix, or no "
                  "finger-up seen)" % (len(sw) - len(lat), len(sw)))
        slow = [x for x in lat if x > 300]
        if lat:
            print("  over 300 ms: %s" % pct(len(slow), len(lat)))
        outc = Counter(e.get("outcome", "?") for e in ev if e.get("type") == "swipeOutcome")
        if outc:
            print("  swipe outcomes: " + ", ".join("%s=%d" % kv for kv in outc.most_common()))
        # swipe commits followed by a backspaceWord/revert within 10 s
        bs = 0
        sc = [c for c in commits if c.get("commitType") == "swipe"]
        for c in sc:
            for f in ev:
                dt = f.get("t", 0) - c.get("t", 0)
                if 0 <= dt <= 10000 and (f.get("type") == "backspaceWord" or (f.get("type") == "revert" and f.get("kind") == "swipe")):
                    if f.get("type") == "revert" or f.get("word") in (None, c.get("committed")):
                        bs += 1
                        break
        print("  swipe commits then backspaced/reverted: %s" % pct(bs, len(sc)))
        picks_sw = [e for e in ev if e.get("type") == "pick" and e.get("source") == "swipe"]
        if picks_sw:
            print("  swipe strip picks: %d" % len(picks_sw))

    eds = [e for e in ev if e.get("type") == "editor" and isinstance(e.get("suppressedFields"), int)]
    if eds:
        head("Suppressed fields (password etc., content never logged)")
        print("  running count at last editor event: %d" % eds[-1]["suppressedFields"])

    head("Most reverted typed -> corrected")
    pairs = Counter((r.get("typed"), r.get("corrected")) for r in reverts)
    for (t_, c_), n in pairs.most_common(15):
        print("  %3d  %s -> %s" % (n, t_, c_))
    if not pairs:
        print("  none")

    head("Sessions with LM disabled")
    sess = [e for e in ev if e.get("type") == "session"]
    off = []
    for s in sess:
        st = s.get("settings") if isinstance(s.get("settings"), dict) else s
        for k, v in st.items():
            if "lm" in k.lower() and ("enable" in k.lower() or "disabled" in k.lower()):
                if (("disabled" in k.lower()) and v is True) or (("enable" in k.lower()) and v is False):
                    off.append(s)
                    break
    print("  %d of %d sessions" % (len(off), len(sess)))
    lms = [e for e in ev if e.get("type") == "lmState"]
    dis = [e for e in lms if e.get("disabled")]
    if lms:
        print("  lmState events with disabled=true: %s" % pct(len(dis), len(lms)))
        print("  max timeouts seen: %s" % max([e.get("timeouts", 0) or 0 for e in lms]))


if __name__ == "__main__":
    main()
