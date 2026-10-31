#!/usr/bin/env python3
"""
On-device accuracy test for the Instagram reel counter.

Drives Instagram on a connected phone through about a hundred reels and compares Curbox's
stored reel count against an independent ground truth:

* Counter: read straight from Curbox's Room database with `run-as` (debug builds only).
* Truth: a screenshot after every action, read with macOS Vision OCR. A reel is identified by
  its author, caption, and the like, comment, and save numbers. A reel counts as new when it
  was not seen earlier in the run.

uiautomator is deliberately avoided, since UiAutomation can suppress other accessibility
services while it is connected.

Phases: slow forward swipes, back swipes over seen reels, forward again over seen reels, a fast
burst (verified afterwards by walking back slowly), pausing, a tab switch, leaving the app, and
reels opened from Explore.

Usage:
    python3 tools/reel-counter-test/reel_counter_test.py [--reels 100] [--serial ID]

Requirements: macOS with Xcode command line tools (swiftc), adb, a debug Curbox build with
the accessibility service on, and Instagram signed in. Exit code 0 means every step matched.
"""

import argparse
import json
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

INSTAGRAM = "com.instagram.android"
TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = TOOL_DIR.parent.parent

USERNAME_RE = re.compile(r"^[A-Za-z0-9._]{2,30}$")
NUMBER_RE = re.compile(r"^\d[\d,.]*[KkMm]?$")
WORD_RE = re.compile(r"\w+", re.UNICODE)

# Screen positions as fractions of the screen size, measured on Instagram's current layout.
TAB_Y = 0.95
HOME_TAB_X = 0.10
REELS_TAB_X = 0.30
EXPLORE_TAB_X = 0.70
EXPLORE_GRID_ITEM = (0.50, 0.42)
SWIPE_LOW_Y = 0.70
SWIPE_HIGH_Y = 0.25

SETTLE_S = 1.6
LATE_WRITE_S = 1.5


# ---------------------------------------------------------------- device


class Device:
    def __init__(self, serial: str | None):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.width, self.height = self._screen_size()

    def run(self, *args: str, timeout: float = 30, check: bool = True) -> subprocess.CompletedProcess:
        result = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
        return result

    def shell(self, command: str) -> str:
        return self.run("shell", command).stdout.decode(errors="replace").strip()

    def _screen_size(self) -> tuple[int, int]:
        output = self.shell("wm size")
        match = re.findall(r"(\d+)x(\d+)", output)
        if not match:
            raise RuntimeError(f"cannot read screen size: {output}")
        width, height = match[-1]
        return int(width), int(height)

    def tap(self, fx: float, fy: float):
        self.shell(f"input tap {int(fx * self.width)} {int(fy * self.height)}")

    def swipe(self, fy_from: float, fy_to: float, duration_ms: int):
        x = int(0.5 * self.width)
        self.shell(
            f"input swipe {x} {int(fy_from * self.height)} {x} {int(fy_to * self.height)} {duration_ms}"
        )

    def key(self, code: str):
        self.shell(f"input keyevent {code}")

    def launch(self, package: str):
        self.shell(f"monkey -p {package} -c android.intent.category.LAUNCHER 1")

    def focused_package(self) -> str:
        output = self.shell("dumpsys window | grep mCurrentFocus")
        match = re.search(r"u0 ([\w.]+)/", output)
        return match.group(1) if match else output

    def screenshot(self, path: Path):
        path.write_bytes(self.run("exec-out", "screencap", "-p").stdout)


# ---------------------------------------------------------------- counter


class CounterReader:
    """Reads Curbox's reel totals from a copy of its database."""

    def __init__(self, device: Device, package: str):
        self.device = device
        self.package = package

    def _pull(self, name: str, target: Path) -> bool:
        result = self.device.run("exec-out", "run-as", self.package, "cat", f"databases/{name}", check=False)
        if result.returncode != 0 or not result.stdout or b"No such file" in result.stdout[:200]:
            return False
        target.write_bytes(result.stdout)
        return True

    def read(self) -> tuple[int, int]:
        """Returns (all reels counted, Instagram reels in per app stats)."""
        last_error: Exception | None = None
        for _ in range(4):
            with tempfile.TemporaryDirectory() as tmp:
                db = Path(tmp) / "curbox_db"
                if not self._pull("curbox_db", db):
                    raise RuntimeError(
                        f"cannot read the database of {self.package}. Is it a debug build?"
                    )
                self._pull("curbox_db-wal", Path(tmp) / "curbox_db-wal")
                try:
                    with sqlite3.connect(db) as connection:
                        total = connection.execute(
                            "SELECT COALESCE(SUM(count), 0) FROM reel_stats"
                        ).fetchone()[0]
                        per_app = connection.execute(
                            "SELECT COALESCE(SUM(reelCount), 0) FROM reel_usage_stats WHERE packageName = ?",
                            (INSTAGRAM,),
                        ).fetchone()[0]
                    return int(total), int(per_app)
                except sqlite3.DatabaseError as error:
                    last_error = error
                    time.sleep(0.3)
        raise RuntimeError(f"database copy unreadable: {last_error}")


# ---------------------------------------------------------------- ground truth


def normalize_author(author: str) -> str:
    """Folds characters OCR mixes up, such as `laim_studios` and `1aim_studios`."""
    return author.lower().translate(str.maketrans({"1": "l", "i": "l", "|": "l", "0": "o"}))


def authors_match(first: str, second: str) -> bool:
    """Allows one OCR slip, such as a dropped trailing underscore."""
    a, b = normalize_author(first), normalize_author(second)
    if a == b:
        return True
    if abs(len(a) - len(b)) > 1 or min(len(a), len(b)) < 6:
        return False
    previous = list(range(len(b) + 1))
    for i, char_a in enumerate(a, 1):
        current = [i]
        for j, char_b in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (char_a != char_b)))
        previous = current
    return previous[-1] <= 1


@dataclass
class Reel:
    author: str
    caption_words: frozenset[str]
    numbers: tuple[str, ...]

    def same_as(self, other: "Reel") -> bool:
        # One creator often posts many reels with a templated caption and tiny numbers,
        # so every signal has to agree.
        if not authors_match(self.author, other.author):
            return False
        if len(self.numbers) >= 2 and len(other.numbers) >= 2:
            matches = sum(1 for number in self.numbers if number in other.numbers)
            if matches * 2 < min(len(self.numbers), len(other.numbers)):
                return False
        if not self.caption_words and not other.caption_words:
            return True
        union = self.caption_words | other.caption_words
        return len(self.caption_words & other.caption_words) / len(union) >= 0.6

    def label(self) -> str:
        return self.author


class ReelReader:
    """Identifies the reel on screen from a screenshot."""

    def __init__(self, device: Device, work_dir: Path):
        self.device = device
        self.work_dir = work_dir
        self.ocr_binary = work_dir / "ocr"
        self._build_ocr()

    def _build_ocr(self):
        source = TOOL_DIR / "ocr.swift"
        if self.ocr_binary.exists() and self.ocr_binary.stat().st_mtime > source.stat().st_mtime:
            return
        if not shutil.which("swiftc"):
            raise RuntimeError("swiftc not found. Install the Xcode command line tools.")
        print("Compiling OCR helper...", flush=True)
        subprocess.run(["swiftc", "-O", str(source), "-o", str(self.ocr_binary)], check=True)

    def read(self, screenshot: Path) -> Reel | None:
        self.device.screenshot(screenshot)
        output = subprocess.run([str(self.ocr_binary), str(screenshot)], capture_output=True, check=True)
        return self.parse(json.loads(output.stdout))

    def parse(self, lines: list[dict]) -> Reel | None:
        width, height = self.device.width, self.device.height
        author_line = None
        for line in sorted(lines, key=lambda item: item["top"]):
            token = line["text"].strip().split(" ")[0].strip("✓•·")
            # The author sits just right of the profile picture, at the bottom left.
            in_author_area = 0.12 * width <= line["left"] <= 0.19 * width and 0.70 * height <= line["top"] <= 0.93 * height
            # Usernames are large; ad fine print in the same area is not.
            tall_enough = line["bottom"] - line["top"] >= 0.013 * height
            if in_author_area and tall_enough and USERNAME_RE.match(token):
                author_line = (line, token)
                break
        if author_line is None:
            return None
        line, author = author_line

        caption_words: set[str] = set()
        for other in lines:
            if other["left"] < 0.08 * width and line["top"] < other["top"] < 0.93 * height:
                caption_words.update(word.lower() for word in WORD_RE.findall(other["text"]))

        numbers = tuple(
            other["text"].strip()
            for other in sorted(lines, key=lambda item: item["top"])
            if other["left"] > 0.85 * width
            and 0.40 * height <= other["top"] <= 0.93 * height
            and NUMBER_RE.match(other["text"].strip())
        )
        return Reel(author, frozenset(caption_words), numbers)


# ---------------------------------------------------------------- curbox trace


class TraceStream:
    """Streams Curbox's debug counting trace for the whole run, so no line is lost."""

    def __init__(self, device: Device):
        device.run("logcat", "-c", check=False)
        self.lines: list[str] = []
        self.lock = threading.Lock()
        self.process = subprocess.Popen(
            device.base + ["logcat", "-s", "ReelsCountTracker:I"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for raw in self.process.stdout:
            line = raw.decode(errors="replace").rstrip()
            if "ReelsCountTracker" in line:
                with self.lock:
                    self.lines.append(line.split(": ", 1)[-1])

    def mark(self) -> int:
        time.sleep(0.2)
        with self.lock:
            return len(self.lines)

    def since(self, mark: int) -> list[str]:
        time.sleep(0.2)
        with self.lock:
            return self.lines[mark:]

    def close(self):
        self.process.terminate()


# ---------------------------------------------------------------- test run


@dataclass
class Step:
    phase: str
    action: str
    reel: str
    expected: int | None
    actual: int
    note: str = ""
    trace: list[str] = field(default_factory=list)

    @property
    def verified(self) -> bool:
        return self.expected is not None

    @property
    def ok(self) -> bool:
        return not self.verified or self.expected == self.actual


@dataclass
class Phase:
    name: str
    expected: int = 0
    actual: int = 0
    steps: int = 0
    mismatches: int = 0
    unverified: int = 0


@dataclass
class Run:
    device: Device
    counter: CounterReader
    reader: ReelReader
    out_dir: Path
    keep_screens: bool
    trace: TraceStream
    trace_mark: int = 0
    seen: list[Reel] = field(default_factory=list)
    steps: list[Step] = field(default_factory=list)
    phases: dict[str, Phase] = field(default_factory=dict)
    last_total: int = 0
    last_per_app: int = 0
    storage_mismatches: int = 0
    shot_index: int = 0

    # -- helpers

    def is_new(self, reel: Reel) -> bool:
        if any(reel.same_as(seen) for seen in self.seen):
            return False
        self.seen.append(reel)
        return True

    def observe(self) -> tuple[Reel | None, Path]:
        self.shot_index += 1
        path = self.out_dir / f"step_{self.shot_index:04d}.png"
        reel = self.reader.read(path)
        if reel is None:
            time.sleep(1.0)
            reel = self.reader.read(path)
        return reel, path

    def counter_delta(self, expected: int) -> int:
        total, per_app = self.counter.read()
        if total - self.last_total != expected:
            time.sleep(LATE_WRITE_S)
            total, per_app = self.counter.read()
        delta = total - self.last_total
        if per_app - self.last_per_app != delta:
            self.storage_mismatches += 1
        self.last_total, self.last_per_app = total, per_app
        return delta

    def clear_trace(self):
        self.trace_mark = self.trace.mark()

    def read_trace(self) -> list[str]:
        return self.trace.since(self.trace_mark)

    def ensure_instagram(self):
        package = self.device.focused_package()
        if package != INSTAGRAM:
            raise RuntimeError(
                f"Instagram lost focus ({package}). A Curbox block screen or a popup may be showing."
            )

    def record(self, phase: str, action: str, reel: Reel | None, expected: int | None, actual: int,
               screenshot: Path | None, note: str = ""):
        step = Step(phase, action, reel.label() if reel else "?", expected, actual, note, self.read_trace())
        self.steps.append(step)
        summary = self.phases.setdefault(phase, Phase(phase))
        summary.steps += 1
        if step.verified:
            summary.expected += expected
            summary.actual += actual
        else:
            summary.unverified += 1
        if not step.ok:
            summary.mismatches += 1
        mark = "ok " if not step.verified or step.ok else "BAD"
        if not step.verified:
            mark = " ? "
        shown = "  ?" if expected is None else f"{expected:+d}"
        print(f"  {mark} {action:<22} {step.reel[:28]:<28} expected {shown}  counted {actual:+d} {note}",
              flush=True)
        if not step.ok:
            for line in step.trace:
                print(f"        curbox: {line}", flush=True)
        if screenshot and screenshot.exists() and step.ok and not self.keep_screens:
            screenshot.unlink()

    def step(self, phase: str, action: str, perform, settle: float = SETTLE_S):
        self.clear_trace()
        perform()
        time.sleep(settle)
        self.ensure_instagram()
        reel, shot = self.observe()
        note = ""
        if reel is None:
            note = "(screen unreadable, not verified)"
            expected = None
        else:
            expected = 1 if self.is_new(reel) else 0
        actual = self.counter_delta(-1 if expected is None else expected)
        self.record(phase, action, reel, expected, actual, shot, note)
        return reel

    # -- actions

    def forward(self, duration_ms: int = 200):
        self.device.swipe(SWIPE_LOW_Y, SWIPE_HIGH_Y, duration_ms)

    def back(self):
        self.device.swipe(SWIPE_HIGH_Y, SWIPE_LOW_Y, 200)

    def open_reels_tab(self):
        self.device.tap(REELS_TAB_X, TAB_Y)

    # -- phases

    def start(self):
        print("Opening Instagram reels...", flush=True)
        self.device.launch(INSTAGRAM)
        time.sleep(4)
        self.ensure_instagram()
        reel, shot = self.observe()
        if reel is None:
            # Tapping the Reels tab while already on it refreshes the feed, so only tap when needed.
            self.open_reels_tab()
            time.sleep(4)
            reel, shot = self.observe()
        if reel is None:
            raise RuntimeError(f"Not on a reel after opening the Reels tab. See {shot}")
        self.is_new(reel)
        shot.unlink(missing_ok=True)
        # The landing reel may or may not have been counted before the run, so it is the baseline.
        time.sleep(1)
        self.last_total, self.last_per_app = self.counter.read()
        print(f"Baseline: {self.last_total} reels counted so far, starting on {reel.label()}\n", flush=True)

    def phase_slow_forward(self, count: int):
        print(f"[slow forward] {count} swipes", flush=True)
        for index in range(count):
            self.step("slow forward", f"swipe {index + 1}/{count}", self.forward)

    def phase_back(self, count: int):
        print(f"[back over seen] {count} swipes", flush=True)
        for index in range(count):
            self.step("back over seen", f"back {index + 1}/{count}", self.back)

    def phase_forward_seen(self, count: int):
        print(f"[forward over seen] {count} swipes", flush=True)
        for index in range(count):
            self.step("forward over seen", f"swipe {index + 1}/{count}", self.forward)

    def phase_burst(self, count: int):
        """Fast swipes, then a slow walk back to find every reel the burst passed."""
        print(f"[fast burst] {count} quick swipes, then a slow walk back", flush=True)
        start_reel, shot = self.observe()
        shot.unlink(missing_ok=True)
        self.clear_trace()
        for _ in range(count):
            self.forward(duration_ms=120)
            time.sleep(0.45)
        time.sleep(SETTLE_S + 0.5)
        self.ensure_instagram()

        end_reel, end_shot = self.observe()
        burst_new = 1 if end_reel and self.is_new(end_reel) else 0
        if end_shot.exists() and not self.keep_screens:
            end_shot.unlink()
        burst_counted = self.counter_delta(-1)

        walk_new = 0
        walk_counted = 0
        reached_start = start_reel is None
        for index in range(count + 6):
            self.back()
            time.sleep(SETTLE_S)
            self.ensure_instagram()
            reel, shot = self.observe()
            delta = self.counter_delta(0)
            walk_counted += delta
            if reel is not None and start_reel is not None and reel.same_as(start_reel):
                reached_start = True
                shot.unlink(missing_ok=True)
                break
            if reel is not None and self.is_new(reel):
                walk_new += 1
            if not self.keep_screens:
                shot.unlink(missing_ok=True)

        expected = burst_new + walk_new
        actual = burst_counted + walk_counted
        note = f"(passed {walk_new} more on the walk back"
        if walk_counted:
            note += f", {walk_counted} counted only on the walk back"
        if not reached_start:
            note += ", never got back to the start reel"
        note += ")"
        self.record("fast burst", f"{count} quick swipes", end_reel, expected, actual, None, note)

        print("  walking forward again over the burst reels", flush=True)
        for index in range(walk_new + burst_new):
            self.step("fast burst", f"revisit {index + 1}", self.forward)

    def phase_pause(self):
        print("[pause and play]", flush=True)

        def pause_and_play():
            self.device.tap(0.5, 0.45)
            time.sleep(2.5)
            self.device.tap(0.5, 0.45)

        self.step("pause", "pause, wait, play", pause_and_play)

    def phase_tab_switch(self):
        print("[tab switch]", flush=True)

        def switch():
            self.device.tap(HOME_TAB_X, TAB_Y)
            time.sleep(3)
            self.open_reels_tab()

        self.step("tab switch", "home tab, reels tab", switch, settle=3.5)

    def phase_leave_app(self):
        print("[leave and return]", flush=True)

        def leave():
            self.device.key("KEYCODE_HOME")
            time.sleep(3)
            self.device.launch(INSTAGRAM)

        self.step("leave app", "home, relaunch", leave, settle=4)

    def phase_explore(self, count: int):
        print(f"[explore viewer] open a reel, {count} swipes, reopen", flush=True)

        def open_item():
            self.device.tap(EXPLORE_TAB_X, TAB_Y)
            time.sleep(4)
            self.device.tap(*EXPLORE_GRID_ITEM)

        self.step("explore", "open grid reel", open_item, settle=4)
        for index in range(count):
            self.step("explore", f"swipe {index + 1}/{count}", self.forward)

        def reopen():
            self.device.key("KEYCODE_BACK")
            time.sleep(2.5)
            self.device.tap(*EXPLORE_GRID_ITEM)

        self.step("explore", "back, reopen same", reopen, settle=4)

        def return_to_reels():
            self.device.key("KEYCODE_BACK")
            time.sleep(2.5)
            self.open_reels_tab()

        self.step("explore", "back to reels tab", return_to_reels, settle=4)

    # -- report

    def report(self, started: float) -> bool:
        expected = sum(phase.expected for phase in self.phases.values())
        actual = sum(phase.actual for phase in self.phases.values())
        mismatched = [step for step in self.steps if not step.ok]
        unverified = [step for step in self.steps if not step.verified]

        print("\n" + "=" * 80)
        print(f"{'phase':<20}{'steps':>7}{'truth':>8}{'counted':>9}{'bad steps':>11}{'unverified':>12}")
        print("-" * 80)
        for phase in self.phases.values():
            print(f"{phase.name:<20}{phase.steps:>7}{phase.expected:>8}{phase.actual:>9}"
                  f"{phase.mismatches:>11}{phase.unverified:>12}")
        print("-" * 80)
        print(f"{'total':<20}{len(self.steps):>7}{expected:>8}{actual:>9}{len(mismatched):>11}{len(unverified):>12}")
        print("=" * 80)

        error = abs(actual - expected)
        accuracy = 100.0 * (1 - error / expected) if expected else 100.0
        print(f"Reels seen (truth): {expected}   counted: {actual}   accuracy: {accuracy:.1f}%")
        print(f"Distinct reels seen in run: {len(self.seen)}")
        print(f"Unverified steps (screen unreadable): {len(unverified)}, "
              f"counted {sum(step.actual for step in unverified):+d} there")
        print(f"Daily total and per app count disagreed: {self.storage_mismatches} times")
        print(f"Duration: {(time.time() - started) / 60:.1f} min")
        if mismatched:
            print("\nMismatched steps (screenshots kept in the output folder):")
            for step in mismatched:
                print(f"  [{step.phase}] {step.action}: {step.reel} expected {step.expected:+d}, "
                      f"counted {step.actual:+d} {step.note}")
                for line in step.trace:
                    print(f"      curbox: {line}")

        report = {
            "truth": expected,
            "counted": actual,
            "accuracy_percent": round(accuracy, 2),
            "unverified_steps": len(unverified),
            "storage_mismatches": self.storage_mismatches,
            "phases": [phase.__dict__ for phase in self.phases.values()],
            "steps": [{**step.__dict__, "ok": step.ok, "verified": step.verified} for step in self.steps],
        }
        (self.out_dir / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
        print(f"\nReport: {self.out_dir / 'report.json'}")
        return not mismatched and self.storage_mismatches == 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reels", type=int, default=100, help="roughly how many reel swipes to run")
    parser.add_argument("--serial", help="adb serial when several devices are connected")
    parser.add_argument("--package", default="neth.iecal.curbox.debug", help="Curbox debug package")
    parser.add_argument("--out", type=Path, help="output folder for screenshots and report")
    parser.add_argument("--keep-screens", action="store_true", help="keep every screenshot, not only mismatches")
    args = parser.parse_args()

    started = time.time()
    out_dir = args.out or REPO_ROOT / "build" / "reel-counter-test" / time.strftime("%Y%m%d-%H%M%S")
    out_dir.mkdir(parents=True, exist_ok=True)
    work_dir = REPO_ROOT / "build" / "reel-counter-test"

    device = Device(args.serial)
    services = device.shell("settings get secure enabled_accessibility_services")
    if args.package not in services:
        print(f"Curbox accessibility service ({args.package}) is not enabled.", file=sys.stderr)
        return 2

    reels = max(args.reels, 30)
    slow = int(reels * 0.55)
    revisit = max(5, int(reels * 0.08))
    burst = max(5, int(reels * 0.15))
    explore = max(4, int(reels * 0.08))

    trace = TraceStream(device)
    run = Run(device, CounterReader(device, args.package), ReelReader(device, work_dir), out_dir,
              args.keep_screens, trace)
    print(f"Device {device.width}x{device.height}, output in {out_dir}\n")
    try:
        run.start()
        run.phase_slow_forward(slow)
        run.phase_back(revisit)
        run.phase_forward_seen(revisit)
        run.phase_burst(burst)
        run.phase_pause()
        run.phase_tab_switch()
        run.phase_leave_app()
        run.phase_explore(explore)
        run.phase_slow_forward(max(5, reels - slow - burst - explore - 2 * revisit))
    except KeyboardInterrupt:
        print("\nStopped early.")
    except RuntimeError as error:
        print(f"\nStopped: {error}", file=sys.stderr)
    passed = run.report(started)
    trace.close()
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
