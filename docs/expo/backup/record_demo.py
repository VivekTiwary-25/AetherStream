"""Backup recording of the AetherStream expo demo (Python twin of record-demo.spec.ts).

Usage (from the repo root, any folder works):
    pip install playwright imageio-ffmpeg
    python -I docs/expo/backup/record_demo.py https://<public-link>   [--browser msedge|chrome|chromium]

Writes into docs/expo/backup/: demo-raw.webm, moments.json and the GitHub screenshots.
Run post_process.py afterwards (final .webm with the backpressure wait as a time-lapse + GIFs) to cut the per-moment GIFs out of the video.
"""
import argparse
import json
import shutil
import time
from pathlib import Path

from playwright.sync_api import TimeoutError as PwTimeout
from playwright.sync_api import sync_playwright

OUT = Path(__file__).resolve().parent
REPO = "https://github.com/VivekTiwary-25/AetherStream"
PANELS = ["speed", "deliveries", "alerts", "workers", "dead-letters", "chaos", "compare", "timeline", "ops", "scenarios"]
EXPAND_ORDER = ["Speed windows", "Deliveries / min", "Alerts", "Workers", "Dead letters", "Chaos controls",
                "Reference comparison", "Checkpoint & recovery timeline", "Operations", "City scenarios"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--browser", default="msedge", help="Playwright channel: msedge, chrome or chromium")
    ap.add_argument("--skip-github", action="store_true", help="keep the committed GitHub screenshots")
    args = ap.parse_args()
    url = args.url.rstrip("/")
    tmp = OUT / "_video_tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    moments: dict[str, list[float]] = {}

    with sync_playwright() as p:
        launch = {"headless": True}
        if args.browser != "chromium":
            launch["channel"] = args.browser
        browser = p.chromium.launch(**launch)
        version = browser.new_page().request.get(f"{url}/api/version").json()

        ctx = browser.new_context(viewport={"width": 1280, "height": 720},
                                  record_video_dir=str(tmp), record_video_size={"width": 1280, "height": 720})
        # Start with every panel collapsed so the first shot is the whole city; skip the intro splash.
        collapsed = json.dumps({k: True for k in PANELS})
        ctx.add_init_script(f"""
          if (!sessionStorage.getItem('t157-init')) {{
            sessionStorage.setItem('t157-init', '1');
            localStorage.setItem('aetherstream.collapsed-panels', '{collapsed}');
            localStorage.setItem('aetherstream.city-mode', 'live');
          }}""")
        page = ctx.new_page()
        page.set_default_timeout(20_000)
        t0 = time.monotonic()

        def now() -> float:
            return round(time.monotonic() - t0, 2)

        def mark(name: str, start: float) -> None:
            moments[name] = [start, now()]
            print(f"{name}: {moments[name]}")

        def pause(seconds: float) -> None:
            page.wait_for_timeout(int(seconds * 1000))

        page.goto(f"{url}/?city=live", wait_until="domcontentloaded", timeout=60_000)  # force the live Pixi city (headless = software WebGL)
        try:
            page.get_by_role("button", name="Skip", exact=True).click(timeout=8_000)
        except PwTimeout:
            pass
        page.locator("canvas").first.wait_for(timeout=30_000)
        pause(1.5)

        # 1. City with trucks and the glowing event stream (10 s).
        s = now()
        pause(10)
        mark("city", s)

        # 2. Live panels updating.
        s = now()
        for title in EXPAND_ORDER:
            page.get_by_role("button", name=f"Expand {title}", exact=True).click()
            pause(0.25)
        pause(7)
        workers = page.get_by_role("heading", name="Workers", exact=True)
        workers.scroll_into_view_if_needed()
        page.mouse.wheel(0, 250)
        pause(4)
        mark("panels", s)

        # 3. Kill worker -> recovery -> recovered. The banner/header show the execution state; the timeline
        #    panel keeps the amber "Recovering from checkpoint #N" band afterwards.
        s = now()
        kill = page.get_by_role("button", name="Kill worker", exact=True)
        kill.scroll_into_view_if_needed()
        page.evaluate("window.scrollBy(0, 150)")
        pause(2)
        kill.click()
        pause(3)
        page.get_by_role("button", name="Confirm kill", exact=True).click()
        page.evaluate("window.scrollTo(0, 0)")  # banner + Workers panel show the recovery
        banner = page.locator(".execution-banner")
        recovering = "() => /Recovering/.test(document.querySelector('.execution-banner')?.innerText || '')"
        try:
            page.wait_for_function(recovering, timeout=40_000, polling=100)
            print("banner:", banner.inner_text().replace(chr(10), " "), "at", now())
            page.wait_for_function(f"() => !({recovering})()", timeout=90_000, polling=100)
            print("recovered, banner:", banner.inner_text().replace(chr(10), " "), "at", now())
        except PwTimeout:
            print("WARN: banner never showed Recovering")
        pause(4)
        page.get_by_role("heading", name="Checkpoint & recovery timeline", exact=True).scroll_into_view_if_needed()
        page.evaluate("window.scrollBy(0, 200)")
        pause(5)
        mark("kill_recovery", s)

        # 4. Trucks keep driving (collapse everything again for a clear city view).
        s = now()
        page.evaluate("window.scrollTo(0, 0)")
        for title in EXPAND_ORDER:
            page.get_by_role("button", name=f"Collapse {title}", exact=True).click()
        pause(4)
        mark("trucks_after_recovery", s)
        for title in EXPAND_ORDER:
            page.get_by_role("button", name=f"Expand {title}", exact=True).click()
        pause(0.5)

        # 5. Compare with reference -> "N / N results match".
        s = now()
        btn = page.get_by_role("button", name="Compare with reference", exact=True)
        btn.scroll_into_view_if_needed()
        page.evaluate("window.scrollBy(0, 200)")
        pause(1.5)
        btn.click()
        try:
            page.get_by_text("results match").first.wait_for(timeout=90_000)
            print("compare done at", now())
        except PwTimeout:
            print("WARN: compare result not seen")
        page.get_by_text("results match").first.scroll_into_view_if_needed()
        pause(7)
        mark("compare", s)

        # 6. Backpressure amber: slow the sink to 200 ms/record; sink queues fill (bounded at 1,000) and the engine
        #    pushes back. Filling takes ~60 s on the live stack, so that wait is marked and shown as a time-lapse.
        s = now()
        sel = page.locator('select[aria-label="Slow down output"]')
        sel.scroll_into_view_if_needed()
        page.evaluate("window.scrollBy(0, 150)")
        pause(1.5)
        sel.select_option("200")
        pause(3)
        page.evaluate("window.scrollTo(0, 0)")
        workers.scroll_into_view_if_needed()
        page.evaluate("window.scrollBy(0, -60)")
        pause(2)
        wait_start = now()
        try:
            page.get_by_text("Backpressured", exact=True).first.wait_for(timeout=150_000)
            print("backpressured at", now())
        except PwTimeout:
            print("WARN: backpressure not seen")
        moments["backpressure_wait"] = [wait_start, now()]
        pause(1)
        page.evaluate("window.scrollTo(0, 0)")
        pause(7)
        sel.scroll_into_view_if_needed()
        page.evaluate("window.scrollBy(0, 150)")
        pause(1.5)
        sel.select_option("0")
        pause(3)
        mark("backpressure", s)

        # 7. Footer version.
        s = now()
        page.locator("footer").scroll_into_view_if_needed()
        page.evaluate("window.scrollTo(0, document.documentElement.scrollHeight)")
        pause(8)
        mark("footer", s)
        footer_text = page.locator("footer").inner_text()

        video = page.video
        ctx.close()
        final = OUT / "demo-raw.webm"  # post_process.py turns this into demo-backup.webm
        video.save_as(str(final))

        # GitHub: green Actions run list + the CD deploy run for the deployed commit (public pages).
        cd = None
        if not args.skip_github:  # otherwise keep the committed PNGs
            gh = browser.new_page(viewport={"width": 1440, "height": 900})
            gh.goto(f"{REPO}/actions?query=branch%3Amain+is%3Asuccess", wait_until="networkidle", timeout=60_000)
            gh.screenshot(path=str(OUT / "github-actions-green.png"))
            runs = gh.request.get(f"https://api.github.com/repos/VivekTiwary-25/AetherStream/actions/runs?head_sha={version['commit']}").json()
            cd = next((r for r in runs.get("workflow_runs", []) if r["name"] == "cd"), None)
            if cd:
                gh.goto(cd["html_url"], wait_until="networkidle", timeout=60_000)
                gh.screenshot(path=str(OUT / "github-cd-deploy.png"))
        browser.close()

    shutil.rmtree(tmp, ignore_errors=True)
    meta = {"url": url, "version": version, "footer": footer_text, "moments": moments,
            "cdRun": cd["html_url"] if cd else None, "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    (OUT / "moments.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
