#!/usr/bin/env python3
"""Builds the Quila Visuals technical report for the Ads Eventing platform.

    python3 docs/report/build_report.py . docs/report/Quila-Visuals-Ads-Eventing-Report.pdf

Needs reportlab and the DejaVu fonts (fonts-dejavu-core). Logos come from docs/brand/.
"""
import collections
import datetime
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, CondPageBreak, Frame, KeepTogether, NextPageTemplate, PageBreak,
                                PageTemplate, Paragraph, Preformatted, Spacer, Table, TableStyle)
from reportlab.platypus.tableofcontents import TableOfContents

REPO, OUT = sys.argv[1], sys.argv[2]
TODAY = datetime.date.today().strftime("%d %B %Y")

# ---------------------------------------------------------------- fonts and brand
F = "/usr/share/fonts/truetype/dejavu/"
pdfmetrics.registerFont(TTFont("Sans", F + "DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("Sans-Bold", F + "DejaVuSans-Bold.ttf"))
pdfmetrics.registerFont(TTFont("Mono", F + "DejaVuSansMono.ttf"))
pdfmetrics.registerFont(TTFont("Mono-Bold", F + "DejaVuSansMono-Bold.ttf"))
pdfmetrics.registerFont(TTFont("Serif", F + "DejaVuSerif.ttf"))
from reportlab.pdfbase.pdfmetrics import registerFontFamily
registerFontFamily("Sans", normal="Sans", bold="Sans-Bold", italic="Sans", boldItalic="Sans-Bold")
registerFontFamily("Mono", normal="Mono", bold="Mono-Bold", italic="Mono", boldItalic="Mono-Bold")

INK = colors.HexColor("#141a26")
INK2 = colors.HexColor("#4a5262")
MUTED = colors.HexColor("#7a8292")
RULE = colors.HexColor("#d9dde4")
SOFT = colors.HexColor("#f3f4f6")
ACCENT = colors.HexColor("#e9a512")      # brand accent
ACCENT_INK = colors.HexColor("#8a5d00")
GOOD = colors.HexColor("#2c7a4b")

BRAND = "QUILA VISUALS"
DOC_TITLE = "Ads Eventing Platform"
DOC_SUB = "Technical report: design, implementation, verification and source code"


BRAND_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "brand")
LOGO_DARK = os.path.join(BRAND_DIR, "quila-visuals-logo.png")
LOGO_WHITE = os.path.join(BRAND_DIR, "quila-visuals-logo-white.png")


def draw_logo(c, x, y, height, light=False):
    """The QUILA VISUALS logo with its bottom-left corner at (x, y). Returns its width."""
    from reportlab.lib.utils import ImageReader
    img = ImageReader(LOGO_WHITE if light else LOGO_DARK)
    iw, ih = img.getSize()
    w = height * iw / ih
    c.drawImage(img, x, y, width=w, height=height, mask="auto")
    return w


# ---------------------------------------------------------------- styles
def S(name, **kw):
    base = dict(fontName="Sans", fontSize=9.6, leading=14, textColor=INK, alignment=TA_LEFT)
    base.update(kw)
    return ParagraphStyle(name, **base)


H1 = S("H1", fontName="Sans-Bold", fontSize=19, leading=24, spaceBefore=4, spaceAfter=10, textColor=INK)
H2 = S("H2", fontName="Sans-Bold", fontSize=12.5, leading=17, spaceBefore=12, spaceAfter=5, textColor=INK)
H3 = S("H3", fontName="Sans-Bold", fontSize=10.2, leading=14, spaceBefore=8, spaceAfter=3, textColor=INK2)
BODY = S("Body", spaceAfter=6)
SMALL = S("Small", fontSize=8.2, leading=11, textColor=INK2)
EYEBROW = S("Eyebrow", fontName="Sans-Bold", fontSize=7.6, leading=10, textColor=ACCENT_INK, spaceAfter=2)
BUL = S("Bul", leftIndent=12, bulletIndent=2, spaceAfter=3)
CELL = S("Cell", fontSize=8, leading=10.6)
CELLB = S("CellB", fontName="Sans-Bold", fontSize=8, leading=10.6)
CELLM = S("CellM", fontName="Mono", fontSize=7.4, leading=9.6)
HEADC = S("HeadC", fontName="Sans-Bold", fontSize=7.4, leading=9.5, textColor=MUTED)
H2A = S("H2A", fontName="Sans-Bold", fontSize=12.5, leading=17, spaceBefore=12, spaceAfter=5, textColor=INK)
FILEH = S("FileH", fontName="Mono-Bold", fontSize=8.4, leading=11, textColor=INK, spaceBefore=8, spaceAfter=1)
FILEMETA = S("FileMeta", fontSize=7.6, leading=10, textColor=MUTED, spaceAfter=3)
CODE = ParagraphStyle("Code", fontName="Mono", fontSize=6.5, leading=8.1, textColor=INK, backColor=SOFT,
                      borderPadding=(3, 4, 3, 4), leftIndent=4, rightIndent=4, spaceAfter=6)


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def md(s):
    """Tiny markdown: **bold**, `code` -> reportlab markup (input is trusted text written here)."""
    s = esc(s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"`(.+?)`", r'<font name="Mono" size="8.4">\1</font>', s)
    return s


def P(text, style=BODY):
    return Paragraph(md(text), style)


def bullets(items, style=BUL):
    return [Paragraph(md(i), style, bulletText="•") for i in items]


def table(rows, widths, head=True, mono_cols=(), bold_first=False, zebra=True):
    data = []
    for ri, row in enumerate(rows):
        cells = []
        for ci, v in enumerate(row):
            if isinstance(v, (Paragraph, Table)):
                cells.append(v)
                continue
            st = HEADC if (head and ri == 0) else (CELLM if ci in mono_cols else (CELLB if bold_first and ci == 0 else CELL))
            cells.append(Paragraph(md(str(v)) if not (head and ri == 0) else esc(str(v)).upper(), st))
        data.append(cells)
    t = Table(data, colWidths=widths, repeatRows=1 if head else 0)
    style = [("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 4),
             ("RIGHTPADDING", (0, 0), (-1, -1), 4), ("TOPPADDING", (0, 0), (-1, -1), 3.2),
             ("BOTTOMPADDING", (0, 0), (-1, -1), 3.2), ("LINEBELOW", (0, 0), (-1, -1), 0.4, RULE)]
    if head:
        style += [("LINEBELOW", (0, 0), (-1, 0), 0.9, INK2)]
    if zebra:
        for i in range(1 if head else 0, len(data)):
            if i % 2 == 0:
                style.append(("BACKGROUND", (0, i), (-1, i), SOFT))
    t.setStyle(TableStyle(style))
    return t


# ---------------------------------------------------------------- document with TOC and bookmarks
class Doc(BaseDocTemplate):
    def __init__(self, path, **kw):
        super().__init__(path, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=22 * mm,
                         bottomMargin=18 * mm, title=f"{DOC_TITLE}: technical report", author=BRAND,
                         subject=DOC_SUB, creator=BRAND, **kw)
        frame = Frame(self.leftMargin, self.bottomMargin, self.width, self.height, id="f")
        self.addPageTemplates([PageTemplate("cover", frames=[frame], onPage=cover_page),
                               PageTemplate("body", frames=[frame], onPage=body_page)])
        self._seq = 0

    def beforeDocument(self):
        self._seq = 0

    def afterFlowable(self, f):
        if not isinstance(f, Paragraph):
            return
        name = f.style.name
        if name in ("H1", "H2", "H2A", "FileH"):
            text = f.getPlainText()
            key = f"k{self._seq}"
            self._seq += 1
            self.canv.bookmarkPage(key)
            level = {"H1": 0, "H2": 1, "H2A": 1, "FileH": 2}[name]
            self.canv.addOutlineEntry(text, key, level=level, closed=level > 0)
            if name in ("H1", "H2"):
                self.notify("TOCEntry", (level, text, self.page, key))


def cover_page(c, doc):
    w, h = A4
    c.saveState()
    c.setFillColor(INK)
    c.rect(0, h * 0.42, w, h * 0.58, stroke=0, fill=1)
    c.setFillColor(ACCENT)
    c.rect(0, h * 0.42, w, 3.2, stroke=0, fill=1)
    draw_logo(c, 18 * mm, h - 70 * mm, 46 * mm, light=True)
    c.setFillColor(colors.HexColor("#c9cfd9"))
    c.setFont("Sans-Bold", 9)
    c.drawString(18 * mm, h * 0.42 + 120, "TECHNICAL REPORT")
    c.setFillColor(colors.white)
    c.setFont("Sans-Bold", 34)
    c.drawString(18 * mm, h * 0.42 + 78, "Ads Eventing")
    c.drawString(18 * mm, h * 0.42 + 40, "Platform")
    c.setFillColor(colors.HexColor("#c9cfd9"))
    c.setFont("Sans", 11)
    c.drawString(18 * mm, h * 0.42 + 16, "Design, implementation, verification and complete source code")
    # facts below the band
    jt = sum(tests.values())
    facts = [("Six phases", "plus an installable app"), (f"{TOTAL_LINES:,} lines", f"in {len(SOURCE)} source files"),
             (f"{jt + 7 + RULE_TESTS} automated tests", f"{jt} Java, 7 TypeScript, {RULE_TESTS} alert-rule"),
             ("20,000 events/s", "sustained, exactly-once")]
    col = (w - 36 * mm) / 2
    for i, (a, b) in enumerate(facts):
        x = 18 * mm + (i % 2) * col
        y0 = h * 0.42 - (28 + 26 * (i // 2)) * mm
        c.setFillColor(ACCENT)
        c.rect(x, y0 + 22, 18, 2.2, stroke=0, fill=1)
        c.setFillColor(INK)
        c.setFont("Sans-Bold", 15)
        c.drawString(x, y0 + 4, a)
        c.setFillColor(INK2)
        c.setFont("Sans", 8.4)
        c.drawString(x, y0 - 10, b)
    c.setFillColor(INK2)
    c.setFont("Sans", 9)
    c.drawString(18 * mm, 32 * mm, f"Prepared by {BRAND}")
    c.drawString(18 * mm, 27 * mm, f"Issued {TODAY}")
    c.drawString(18 * mm, 22 * mm, "Repository: github.com/michaelonidba-code/primetech")
    c.setStrokeColor(RULE)
    c.line(18 * mm, 38 * mm, w - 18 * mm, 38 * mm)
    c.restoreState()


def body_page(c, doc):
    w, h = A4
    c.saveState()
    draw_logo(c, 18 * mm, h - 16 * mm, 9 * mm)
    c.setFont("Sans", 7.6)
    c.setFillColor(MUTED)
    c.drawRightString(w - 18 * mm, h - 12.6 * mm, f"{DOC_TITLE} · Technical report")
    c.setStrokeColor(RULE)
    c.setLineWidth(0.5)
    c.line(18 * mm, h - 17 * mm, w - 18 * mm, h - 17 * mm)
    c.line(18 * mm, 13 * mm, w - 18 * mm, 13 * mm)
    c.drawString(18 * mm, 9 * mm, f"© {datetime.date.today().year} {BRAND}. Prepared for the Ads Eventing project.")
    c.setFillColor(INK)
    c.setFont("Sans-Bold", 7.6)
    c.drawRightString(w - 18 * mm, 9 * mm, str(doc.page))
    c.restoreState()


# ---------------------------------------------------------------- repository facts
def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True, check=True).stdout


FILES = [f for f in git("ls-files").split() if not f.endswith((".png", ".jpg", ".pdf", "package-lock.json"))]
GENERATED = {"infra/grafana/dashboards/ads-eventing.json": "generated Grafana dashboard (33 panels)",
             "docs/loadtest/events.json": "load test measurements (data)", "docs/loadtest/full.json": "load test measurements (data)",
             "docs/overview.html": "one-page illustrated overview of the design (open in a browser)"}
SOURCE = [f for f in FILES if not f.endswith(".md")]
PURPOSE_OVERRIDES = {
    "ad-server/src/main/java/dev/primetech/ads/adserver/AdServerApp.java": "Entry point: wires the ad server (DSP clients, Redis, Kafka, pacing view, HTTP).",
    "ad-server/src/main/java/dev/primetech/ads/adserver/AdServerConfig.java": "Ad server settings read from environment variables.",
    "ads-common/src/main/java/dev/primetech/ads/common/DeviceType.java": "Device types accepted on events (CTV, mobile, web, ...).",
    "ads-common/src/main/java/dev/primetech/ads/common/RejectReason.java": "Reasons an event is rejected at the gateway (sent to the dead-letter topic).",
    "ads-common/src/main/java/dev/primetech/ads/common/Topics.java": "Names of every Kafka topic, shared by all services.",
    "event-simulator/src/main/java/dev/primetech/ads/simulator/SimulatorApp.java": "Entry point: runs the traffic simulator against the gateway and ad server.",
    "event-simulator/src/main/java/dev/primetech/ads/simulator/SimulatorConfig.java": "Simulator settings: rate, faults, campaigns, seed, ad server URL.",
    "ingest-gateway/src/main/java/dev/primetech/ads/gateway/GatewayApp.java": "Entry point: starts the ingest gateway HTTP server and Kafka producer.",
    "ingest-gateway/src/main/java/dev/primetech/ads/gateway/GatewayConfig.java": "Gateway settings read from environment variables.",
    "ingest-gateway/src/main/java/dev/primetech/ads/gateway/KafkaEventSink.java": "Writes accepted events to Kafka (acks=all, idempotent) and rejected ones to the dead-letter topic.",
    "mock-dsp/src/main/java/dev/primetech/ads/dsp/DspApp.java": "Entry point: runs one mock DSP with a named bidding preset.",
    "pom.xml": "Maven parent build: modules and pinned dependency versions.",
    "Dockerfile": "Multi-stage build producing one image per Java service.",
    "docker-compose.yml": "The whole stack for local runs: Kafka, Flink, Druid, services, observability, dashboard.",
}


def purpose(f):
    if f in PURPOSE_OVERRIDES:
        return PURPOSE_OVERRIDES[f]
    if f in GENERATED:
        return GENERATED[f]
    t = open(os.path.join(REPO, f), errors="replace").read()
    if f.endswith(".java"):
        m = re.search(r"/\*\*\s*(.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |final |abstract |sealed |static )*(?:class|record|interface|enum)", t, re.S)
        if m:
            s = re.sub(r"\s*\*\s?", " ", m.group(1))
            s = re.sub(r"\{@\w+ ([^}]*)\}", r"\1", s)
            s = re.sub(r"<[^>]+>", "", s)
            s = " ".join(s.split())
            return re.split(r"(?<=[.!?])\s", s)[0]
        return "Test." if "/test/" in f else ""
    m = re.match(r"\s*(?://|#|<!--)\s*(.+?)(?:-->)?\s*$", t.splitlines()[0] if t.strip() else "")
    if m and not t.startswith("#!"):
        return m.group(1).strip()
    for line in t.splitlines()[:6]:
        mm_ = re.match(r"\s*(?://|#)\s*(.+)", line)
        if mm_ and not line.startswith("#!"):
            return mm_.group(1).strip()
    return ""


def lines_of(f):
    return len(open(os.path.join(REPO, f), errors="replace").read().splitlines())


TOTAL_LINES = sum(lines_of(f) for f in SOURCE)

# Tests per module: @Test methods in each module's test sources (equals what Maven runs).
tests = collections.Counter()
for f in FILES:
    if f.endswith(".java") and "/src/test/" in f:
        tests[f.split("/")[0]] += len(re.findall(r"^\s*@Test\b", open(os.path.join(REPO, f)).read(), re.M))
tests = +tests

# Alert rules.
rules = []
cur = None
for line in open(os.path.join(REPO, "infra/prometheus/rules/ads-eventing.yml")):
    if m := re.match(r"\s*- name: (\S+)", line):
        group = m.group(1)
    if m := re.match(r"\s*- alert: (\S+)", line):
        cur = {"alert": m.group(1), "group": group, "for": "", "severity": "", "summary": ""}
        rules.append(cur)
    if cur and (m := re.match(r"\s*for: (\S+)", line)):
        cur["for"] = m.group(1)
    if cur and (m := re.search(r"severity: (\w+)", line)):
        cur["severity"] = m.group(1)
    if cur and (m := re.match(r'\s*summary: "?(.+?)"?\s*$', line)):
        cur["summary"] = re.sub(r"\{\{.*?\}\}", "…", m.group(1))
RULE_TESTS = len(re.findall(r"^  - name:", open(os.path.join(REPO, "infra/prometheus/tests/ads-eventing-test.yml")).read(), re.M))


def md_table(heading):
    """Rows of the first Markdown table under a README heading."""
    text = open(os.path.join(REPO, "README.md")).read()
    i = text.index(heading)
    rows = []
    for line in text[i:].splitlines()[1:]:
        if line.startswith("|"):
            if re.match(r"^\|[-| :]+\|$", line):
                continue
            rows.append([c.strip() for c in line.strip("|").split("|")])
        elif rows:
            break
    return rows


# ---------------------------------------------------------------- content
W = A4[0] - 36 * mm
story = [NextPageTemplate("body"), PageBreak()]

# About + contents
story += [Paragraph("About this document", H1),
          P(f"This report records everything built for the Ads Eventing platform: the architecture and the reasons "
            f"for it, what each of the six phases delivered, how correctness and performance were verified, what "
            f"failed and how it was fixed, how to run and operate the system, and, in Appendix B, the complete source "
            f"code: {len(SOURCE)} files, {TOTAL_LINES:,} lines. Every figure in it was measured on the running system."),
          P(f"Prepared by **{BRAND}** on {TODAY}. Source: github.com/michaelonidba-code/primetech, pull requests "
            f"#2 to #8. The PDF has bookmarks for every section and every source file."),
          Spacer(1, 6)]
toc = TableOfContents()
toc.levelStyles = [S("T0", fontName="Sans-Bold", fontSize=9.6, leading=15, leftIndent=0),
                   S("T1", fontSize=8.6, leading=12, leftIndent=14, textColor=INK2)]
story += [Paragraph("Contents", H2), toc, PageBreak()]

# 1 Executive summary
story += [Paragraph("1. Executive summary", H1),
          P("The project builds, in one repository, a real-time advertising system for a connected-TV ad tier, "
            "modelled on the systems that serve and measure ads at streaming scale. A player asks the **ad server** "
            "for an ad break; the ad server runs an **OpenRTB 2.6 auction** across demand-side platforms and its own "
            "direct campaigns, within budgets set by a **pacing** service. The player reports playback to an **ingest "
            "gateway**; events flow through **Kafka** to **Flink**, which removes duplicates, enriches, flags invalid "
            "traffic and totals delivery exactly once; **Druid** serves reporting. An **audit** service continuously "
            "reconciles independent records of the same money, and **alerts** reach phones through the installable "
            "dashboard app."),
          table([["Outcome", "Result"],
                 ["Event path throughput", "**20,000 events/s** sustained on one 4-vCPU machine (1.7 billion a day), exactly-once"],
                 ["Ingest gateway alone", "**51,500 events/s**, p99 14–23 ms"],
                 ["Ad serving", "**660 auctions/s** across three DSPs, p99 459 ms, nothing shed"],
                 ["Correctness", "every reconciliation exact, including **956,446 = 956,446** events through a crash"],
                 ["Budget pacing", "**0** line items over budget in measured periods; served-spend counters equal the decision log to the cent"],
                 ["Alerting", "fault to phone notification in **49–87 s** in failure drills"],
                 ["Defects found by measurement", "**12** bugs no unit test caught, all fixed"],
                 ["Automated tests", f"**{sum(tests.values())} Java**, 7 TypeScript, {RULE_TESTS} alert-rule cases; CI on every pull request"]],
                [48 * mm, W - 48 * mm], bold_first=True),
          Spacer(1, 8),
          P("Technology: Java 21 (virtual threads), Apache Flink 2.2, Redpanda (Kafka API), Apache Druid 37, Redis, "
            "Nuxt 4 / Vue 3 / TypeScript, Prometheus, Alertmanager, Grafana, Caddy, Docker Compose, GitHub Actions."),
          PageBreak()]

# 2 Architecture
story += [Paragraph("2. Architecture", H1),
          P("Kafka is the system of record between every stage. Druid and Redis are views that can be rebuilt, and the "
            "services that keep state (pacing, audit) rebuild it from the topics when they start, so nothing but Kafka "
            "needs backing up."),
          Paragraph("Data flow", H2),
          *bullets([
              "**Serving:** player → ad server (direct line items + OpenRTB 2.6 auction across 3 DSPs, frequency caps, floors) → ad pod back to the player. Every decision is logged to `ad-decisions.v1`.",
              "**Pacing:** the pacing service reads delivered spend per minute from Flink and writes a throttle and stop threshold per line item to Redis; the ad server reads them every second and writes its served spend back.",
              "**Measurement:** player → ingest gateway (validate, pseudonymise, acknowledge when durable) → `ad-events.v1` → Flink (de-duplicate, enrich, flag invalid traffic, minute totals, exactly-once) → `ad-events-clean.v1`, `line-item-minutes.v1` → Druid.",
              "**Assurance:** the audit service reconciles Flink with Druid, Redis with the decision log, and delivery with budgets; Prometheus scrapes every service; Alertmanager routes alerts to the dashboard, which pushes them to phones."]),
          Paragraph("Components", H2),
          table([["Component", "Built with", "Responsibility"],
                 ["Ingest gateway", "Java 21, JDK HTTP server, Kafka producer", "Validate events one by one, pseudonymise device IDs, acknowledge only when Kafka has the batch"],
                 ["Event bus", "Redpanda (Kafka API)", "Durable, partitioned, replayable log between every stage"],
                 ["Stream processor", "Apache Flink 2.2, RocksDB state", "De-duplicate, enrich, flag invalid traffic, minute totals per line item, exactly-once output"],
                 ["Analytics store", "Apache Druid 37", "Minute rollups with unique-device sketches, SQL"],
                 ["Ad server", "Java 21, OpenRTB 2.6", "Direct demand and DSP auction, frequency caps, floors, pacing, decision log"],
                 ["Mock DSPs", "Java 21", "Three bidders with realistic value and latency distributions"],
                 ["Pacing service", "Java 21", "A closed-loop controller per line item"],
                 ["Audit service", "Java 21", "Continuous reconciliation of independent records"],
                 ["Dashboard", "Nuxt 4, Vue 3, TypeScript, PWA", "Live view, sign-in, read-only API, alerts and push notifications"],
                 ["Observability", "Prometheus, Alertmanager, Grafana", f"{len(rules)} alert rules, an operations dashboard"],
                 ["Edge", "Caddy", "Automatic HTTPS; the only published service in production"]],
                [32 * mm, 50 * mm, W - 82 * mm], bold_first=True),
          PageBreak()]

PHASES = [
    ("3. Phase 1: event backbone", "Pull request #2",
     ["**Event model and validation** (`ads-common`): `ClientEvent` in, `AdEvent` to Kafka; each event validated on its own so one bad event never fails a batch; rejected events go to `ad-events-dlq.v1` with the reason.",
      "**Privacy:** device IDs replaced at the gateway by an HMAC-SHA256 pseudonym when the viewer consented, dropped when not. Raw IDs never reach Kafka.",
      "**Ingest gateway:** JDK HTTP server on virtual threads; Kafka producer with `acks=all` and idempotence, keyed by session; replies 200 only when every event is durable, otherwise 503 with `Retry-After` (at-least-once).",
      "**Traffic simulator:** realistic ad pods, quartile drop-off, clicks, skewed campaigns; injects duplicates, late and invalid events and keeps exact ground truth.",
      "**Dashboard:** Nuxt 4 single-page app with live throughput, funnel and rejections."],
     [["Measure", "Result"], ["Sustained ingest", "about **51,500 events/s** (4.4 billion a day), p50 ≈ 9 ms, p99 14–23 ms"],
      ["Invalid events", "39,464 injected = 39,464 rejected = 39,464 in the dead-letter topic"],
      ["Privacy", "0 raw device IDs in 3,000 sampled Kafka records"],
      ["Tuning", "capping JVM heaps cut p99 from 55–80 ms to 14–23 ms"]]),
    ("4. Phase 2: stream processing and reporting", "Pull request #3",
     ["**De-duplication** by event ID in RocksDB state with a 30-minute TTL removes the duplicates at-least-once ingest lets through.",
      "**Enrichment** with campaign metadata through broadcast state; events that arrive before their metadata are buffered and released.",
      "**Invalid traffic:** a device above 20 impressions in an event-time minute is flagged, excluded from spend, and kept.",
      "**Minute totals per line item:** event-time windows with 10 minutes of allowed lateness, emitted as revisions; `CoalescingLateTrigger` limits late re-firings (217 revisions of one minute became 6).",
      "**Exactly-once output:** checkpoints every 10 s, Kafka transactions on both sinks; `RestoreEventTime` restores timestamps lost by broadcast state.",
      "**Druid 37:** Kafka supervisor reading committed records only, minute rollup, HLL unique devices; dashboard panels for Flink health and campaign delivery."],
     [["Measure", "Result"], ["Clean events vs ground truth", "**636,872 = 636,872**, identical per event type; 6,397 duplicates injected, 0 in output"],
      ["Line item minutes vs recount", "306 / 306 windows match"],
      ["Crash drill (TaskManager killed)", "**956,446 = 956,446**, 0 missing, 0 duplicated; 612 / 612 minutes match"],
      ["Druid vs clean topic", "identical totals, including 8,537,992,000 µ$ of spend"]]),
    ("5. Phase 3: ad serving with OpenRTB auctions", "Pull request #4",
     ["**OpenRTB 2.6 model** for connected-TV ad pods (`podid`, `slotinpod`, `poddur`), with price macros.",
      "**Mock DSPs:** premium (selective, fast), budget (bids on most, cheap) and slow (high value, long latency tail).",
      "**Ad server:** pseudonymises the device, fans out to DSPs in parallel within 120 ms while filtering direct line items, applies frequency caps with one Redis `MGET`, runs a per-slot pod auction (floors, one ad per advertiser per break, duration fit, first or second price), sends win notices and logs every decision.",
      "**Admission control:** bounded in-flight decisions with an immediate 503, so overload degrades instead of hanging.",
      "The simulator now requests every break from the ad server and reports auction ID and clearing price on its events."],
     [["Measure", "Result"], ["Clean events vs ground truth", "**187,027 = 187,027**"],
      ["Impressions joined to their auction at the clearing price", "**44,610 / 44,610**"],
      ["Druid spend by source vs the auction join", "identical: $922.73 direct, $750.67 programmatic"],
      ["Latency at 227 / 542 / 808 auctions/s", "p50/p99 91/129 ms, 104/318 ms, 149/333 ms"]]),
    ("6. Phase 4: budgets and pacing", "Pull request #5",
     ["**Line items** have a tier (guaranteed or standard) and a budget per pacing period; guaranteed line items take a slot ahead of the auction.",
      "**Pacing service:** one controller per line item, learning its yield and setting `throttle = remaining budget ÷ remaining time ÷ yield`; cuts up to 4× per tick, raises at most 2×, catches up at most at 2× the even rate.",
      "**Two clocks:** delivered spend (Flink, exact, a minute old) budgets; served spend (ad server, immediate) steers; a stop threshold in served spend stops a line item the moment it is spent.",
      "**Ad server** reads pacing state from Redis once a second; if it is stale it falls back to a small fixed throttle and a hard stop at budget, so it can under-deliver but never overspend."],
     [["Measure", "Result"], ["Line items over budget by > 1%", "**0** in both measured periods"],
      ["Median delivered / budget", "96.9% and 96.4% (stops early by design)"],
      ["Served-spend counters vs decision log", "identical: $2,406.02 and $2,530.03"],
      ["Closed-loop simulation", "98.9–99.2% of budget delivered, worst deviation 0.8–5.9%"]]),
    ("7. Installable app and hosted API", "Pull request #6",
     ["**Progressive web app:** manifest, icons (including maskable and Apple touch), service worker; installs on Windows, macOS, Android and iOS and opens offline. API responses are never cached.",
      "**Sign-in:** scrypt password hashes in configuration; HMAC-signed `HttpOnly; Secure; SameSite=Strict` session cookies; changing a password ends its sessions.",
      "**Read-only API:** every data route is GET and requires a session; login limited to 20 failures per 15 minutes per address and 10 per user name; fails closed with 503 when misconfigured.",
      "**Hosting:** a production overlay with Caddy for automatic HTTPS and HSTS; only ports 80 and 443 published."],
     [["Check", "Result"], ["Internal ports from outside", "all closed"],
      ["Data routes without / with a session", "401 / live data"],
      ["Form login, cross-origin login, tampered cookie, POST to data", "415, 403, 401, 405"],
      ["21 failed logins with spoofed X-Forwarded-For", "21st refused (429): spoofing has no effect"]]),
    ("8. Phase 5: audit and observability", "Pull request #7",
     ["**Audit service:** every minute compares Flink's totals with Druid's per line item and minute, Redis served spend with the decision log, delivered spend with budgets, and delivered with served; results as metrics, records on `audit-results.v1`, and a dashboard panel naming the line items.",
      f"**Prometheus, Alertmanager, Grafana:** {len(rules)} alert rules with {RULE_TESTS} `promtool` unit tests, inhibition so one cause sends one notification, and a provisioned operations dashboard.",
      "**Alerts to phones:** Alertmanager calls the dashboard's webhook (bearer token, blocked at Caddy), which sends encrypted Web Push (VAPID) to every subscribed device; push endpoints restricted to the browsers' push services.",
      "**Found by the audit's first run:** the ad server dropped served-spend increments when Redis was slow (4,744 in three minutes; 21 line items over budget). Fixed by writing absolute per-instance totals in the background (`HSET`), so retries can neither lose nor double-count."],
     [["Drill", "Fault → notification on a device"], ["Stop the pacing service", "**87 s**"],
      ["Forge $5 of spend in Redis", "**64 s**, naming the line item and both amounts"],
      ["Kill the Flink TaskManager", "**49 s**; 2,381 minutes across the failure still identical in Flink and Druid"]]),
    ("9. Phase 6: load testing and write-up", "Pull request #8",
     ["**Load test harness** (`loadtest/run.py`): steps the simulator through rising load and records throughput, latency, backlog, freshness, CPU per service and audit status from Prometheus and the running system; `report.py` renders the results.",
      "**Found:** time-only topic retention filled the disk at 40,000 events/s; Redpanda crashed and Flink crash-looped with no alert naming the cause. Fixed with size-based retention, a disk alert on free bytes, and new critical alerts for failing checkpoints and crash loops.",
      "**Profiled:** 14% of Flink's CPU went on compressing small Kafka batches; larger batches cost no visible latency under exactly-once and gave 7% more throughput.",
      "**Design document** (`docs/DESIGN.md`) and a capacity model for 10 billion events a day."],
     [["Offered events/s", "Gateway", "Flink", "Backlog", "Visible p50"],
      ["5,000", "4,998/s", "4,971/s", "none", "5.7 s"], ["10,000", "9,867/s", "9,873/s", "none", "6.2 s"],
      ["20,000", "20,083/s", "20,346/s", "none", "7.7 s"], ["25,000", "24,703/s", "20,513/s", "grows", "13.0 s"],
      ["30,000", "29,601/s", "18,218/s", "grows", "27.1 s"]]),
]
for title, pr, items, results in PHASES:
    story += [Paragraph(title, H1), Paragraph(pr.upper(), EYEBROW), Paragraph("What was built", H2), *bullets(items),
              Paragraph("Measured", H2)]
    ncol = len(results[0])
    widths = [62 * mm, W - 62 * mm] if ncol == 2 else [W / ncol] * ncol
    story += [table(results, widths, bold_first=True), CondPageBreak(60 * mm)]
story.append(PageBreak())

# 10 Testing and verification
story += [Paragraph("10. Testing and verification", H1),
          P("Unit tests cover the logic; end-to-end reconciliations cover the system. Continuous integration (GitHub "
            "Actions) runs the Maven build and tests, the dashboard type check, unit tests and builds, every Docker "
            "image, the production overlay, the Prometheus and Alertmanager configuration and the alert-rule tests."),
          Paragraph("Automated tests by module", H2),
          table([["Module", "Tests"]] + [[m, str(n)] for m, n in sorted(tests.items(), key=lambda x: -x[1])]
                + [["**Java total**", f"**{sum(tests.values())}**"], ["dashboard (TypeScript, node:test)", "7"],
                   ["alert rules (promtool)", str(RULE_TESTS)]],
                [90 * mm, 30 * mm], mono_cols=(0,)),
          Paragraph("End-to-end reconciliations", H2),
          table([["Compared", "Result"],
                 ["Clean events vs simulator ground truth", "636,872 = 636,872"],
                 ["Exactly-once through a TaskManager kill", "956,446 = 956,446"],
                 ["Druid vs the clean topic", "identical events, types, invalid traffic and spend"],
                 ["Impressions joined to auctions at the clearing price", "44,610 / 44,610"],
                 ["Served-spend counters vs the decision log, per period", "identical to the cent"],
                 ["Flink minute totals vs Druid across a TaskManager kill", "2,381 minutes identical"]],
                [100 * mm, W - 100 * mm]),
          PageBreak()]

# 11 Bugs
story += [Paragraph("11. Defects found by measurement", H1),
          P("None of these failed a unit test. Each was found by reconciling, profiling or deliberately breaking the "
            "running system, and each is fixed."),
          table([["#", "Defect", "Found by", "Fix"],
                 ["1", "One minute's total revised 217 times by late events", "reconciliation", "coalescing late trigger (6 revisions)"],
                 ["2", "Flink lost event time on records from broadcast state", "reconciliation", "`RestoreEventTime` operator"],
                 ["3", "A replayed run looked like data loss", "crash drill", "pass the simulator seed through"],
                 ["4", "Ad server hung under overload (13,062 stuck connections)", "load sweep", "admission control, fail-fast Redis pool"],
                 ["5", "Campaign catalog never published (82k events unenriched)", "reconciliation", "fetch metadata first, retry until acknowledged"],
                 ["6", "Pacing overspent up to 51% after a cold start", "live run", "catch-up cap, reset at period rollover"],
                 ["7", "A guessed constant under-delivered by 6%", "live run", "measured serve-to-play delay (15 s)"],
                 ["8", "Pacing fallback could overspend by 20%", "live measurement", "stop at budget in served spend"],
                 ["9", "Served-spend increments dropped under load", "audit", "absolute per-instance totals (`HSET`)"],
                 ["10", "A stale-checkpoint page during recovery", "failure drill", "2-minute restore grace, rule test"],
                 ["11", "A full disk took the system down unannounced", "load test", "size retention, disk and crash-loop alerts"],
                 ["12", "14% of Flink CPU compressing tiny batches", "profiling", "larger producer batches (+7%)"]],
                [8 * mm, 74 * mm, 27 * mm, W - 109 * mm]),
          PageBreak()]

# 12 Security
story += [Paragraph("12. Security and privacy", H1),
          *bullets([
              "**Device privacy:** raw device IDs never leave the gateway; consented IDs become keyed HMAC pseudonyms, others are dropped; rotating the key unlinks history.",
              "**Dashboard sign-in:** scrypt hashes; HMAC-signed session cookies (`HttpOnly`, `Secure`, `SameSite=Strict`, 12 h); a password change ends existing sessions.",
              "**Brute force:** failed logins limited per client address and per user name; unknown names cost the same time as known ones.",
              "**Least exposure:** in production only Caddy (80/443) is reachable; Kafka, Flink, Druid, Redis, Prometheus, Alertmanager and Grafana stay on the internal network.",
              "**Read-only API:** data routes are GET only; the only writes are sign-in, sign-out, notification (un)subscription and the token-protected alert webhook, which Caddy blocks from outside.",
              "**Server-side request forgery:** push subscription endpoints are restricted to the browsers' push services.",
              "**Fails closed:** sign-in on but misconfigured → the API answers 503; it never serves data unauthenticated.",
              "**No data at rest on devices:** the service worker caches only the app shell, never API responses."]),
          PageBreak()]

# 13 Operations
story += [Paragraph("13. Running and operating", H1),
          Paragraph("Run locally", H2),
          Preformatted("docker compose up --build            # whole stack, about 10 GB of memory\n"
                       "open http://localhost:3000            # dashboard (sign-in off locally)\n"
                       "open http://localhost:3001            # Grafana operations dashboard\n"
                       f"mvn verify                            # Java build and {sum(tests.values())} tests", CODE),
          Paragraph("Deploy with HTTPS and sign-in", H2),
          Preformatted("cp deploy/.env.example deploy/.env    # domain, users, secrets\n"
                       "docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml \\\n"
                       "  --env-file deploy/.env up -d --build", CODE),
          Paragraph("Configuration", H2)]
cfg = md_table("## Configuration")
story.append(table(cfg, [62 * mm, 40 * mm, W - 102 * mm], mono_cols=(0,)))
story += [Paragraph("Kafka topics", H2)]
topics = md_table("### Topics")
story.append(table(topics, [W / len(topics[0])] * len(topics[0]), mono_cols=(0,)))
story += [Paragraph("Service endpoints", H2),
          table([["Service", "Endpoints"],
                 ["Ingest gateway :8080", "`POST /v1/events`, `GET /stats`, `/metrics`, `/health`"],
                 ["Ad server :8090", "`POST /v1/ad-requests`, `GET /stats`, `/metrics`, `/health`"],
                 ["Pacing service :8095", "`GET /stats`, `/metrics`, `/health`"],
                 ["Audit service :8096", "`GET /stats`, `/metrics`, `/health`"],
                 ["Dashboard API", "`/api/health`, `/api/auth/login`, `/api/auth/logout`, `/api/auth/me`, `/api/gateway`, `/api/pipeline`, `/api/campaigns`, `/api/adserver`, `/api/pacing`, `/api/audit`, `/api/alerts`, `/api/alerts/webhook`, `/api/push/key`, `/api/push/subscribe`, `/api/push/unsubscribe`"]],
                [40 * mm, W - 40 * mm], bold_first=True),
          Paragraph("Alert rules", H2),
          table([["Alert", "Group", "Severity", "For", "Summary"]] +
                [[r["alert"], r["group"], r["severity"], r["for"] or "—", r["summary"]] for r in rules],
                [44 * mm, 24 * mm, 16 * mm, 11 * mm, W - 95 * mm], mono_cols=(0,)),
          PageBreak()]

# 14 Capacity and pull requests
story += [Paragraph("14. Capacity model", H1),
          P("Per-core rates measured at saturation and rounded down, scaled to 10 billion events a day with a 3× peak "
            "(350,000 events/s, 42,000 auctions/s). An order of magnitude from one small machine, not a quote."),
          table([["Stage", "Per core", "Cores at peak", "Also needs"],
                 ["Ingest gateway", "50,000 events/s", "7", "stateless, behind a load balancer"],
                 ["Kafka", "50,000 events/s", "7", "~12 TB/day before compression; tiered storage; 96+ partitions"],
                 ["Flink", "7,500 events/s", "47", "630 M event IDs of de-duplication state"],
                 ["Druid ingestion", "40,000 events/s", "9", "historicals sized by queries and retention"],
                 ["Ad server", "~750 auctions/s", "56", "regional, close to players"],
                 ["**Total**", "", "**~125**", "**~250 with headroom and zone redundancy**"]],
                [34 * mm, 30 * mm, 24 * mm, W - 88 * mm], bold_first=True),
          Paragraph("15. Pull requests delivered", H1),
          table([["PR", "Branch", "Scope"],
                 ["#2", "phase-1-event-backbone", "Event backbone: gateway, simulator, Kafka topics, dashboard"],
                 ["#3", "phase-2-stream-processing", "Flink exactly-once processing and Druid reporting"],
                 ["#4", "phase-3-ad-serving", "Ad server with OpenRTB 2.6 auctions and mock DSPs"],
                 ["#5", "phase-4-pacing", "Budgets, tiers and closed-loop pacing"],
                 ["#6", "pwa-hosted-api", "Installable app, signed-in API, HTTPS deployment"],
                 ["#7", "phase-5-observability", "Audit service, Prometheus, Alertmanager, Grafana, push alerts"],
                 ["#8", "phase-6-load-and-writeup", "Load tests, profiling, capacity model, design document"]],
                [12 * mm, 52 * mm, W - 64 * mm], mono_cols=(1,)),
          P("Each pull request is stacked on the previous one and passed CI (Java build and tests, dashboard checks, "
            "Docker images, and from Phase 5 the observability checks). Merge them in order, #2 to #8.", SMALL),
          PageBreak()]

# Appendix A: inventory
story += [Paragraph("Appendix A. File inventory", H1),
          P(f"Every file in the repository except images, the npm lock file and Markdown documentation: "
            f"{len(SOURCE)} files, {TOTAL_LINES:,} lines. The purpose is taken from each file's own documentation "
            "comment where it has one.")]
groups = collections.OrderedDict()
for f in sorted(SOURCE):
    top = f.split("/")[0] if "/" in f else "(repository root)"
    groups.setdefault(top, []).append(f)
for top, fs in groups.items():
    rows = [["File", "Lines", "Purpose"]]
    for f in fs:
        short = f[len(top) + 1:] if f.startswith(top + "/") else f
        short = re.sub(r"^src/(main|test)/java/dev/primetech/ads/\w+/", lambda m: f"{m.group(1)}/", short)
        rows.append([short, f"{lines_of(f):,}", purpose(f)])
    story += [Paragraph(f"{top}  ({sum(lines_of(f) for f in fs):,} lines)", H2A),
              table(rows, [62 * mm, 13 * mm, W - 75 * mm], mono_cols=(0,))]
story.append(PageBreak())

# Appendix B: full source
story += [Paragraph("Appendix B. Complete source code", H1),
          P("Every source file in full, grouped by module, with line numbers. Long lines are wrapped and marked "
            "with ↪. The generated Grafana dashboard and load-test result files are listed in Appendix A but not "
            "reproduced. Use the PDF bookmarks to jump to any file.")]
MAXW = 112
for top, fs in groups.items():
    story.append(Paragraph(f"{top}", H2A))
    for f in fs:
        if f in GENERATED:
            continue
        text = open(os.path.join(REPO, f), errors="replace").read().expandtabs(4)
        lines = text.splitlines()
        width = len(str(len(lines)))
        out = []
        for i, line in enumerate(lines, 1):
            first = True
            while True:
                chunk, line = line[:MAXW], line[MAXW:]
                out.append(f"{i:>{width}}  {chunk}" if first else f"{'':>{width}}↪ {chunk}")
                first = False
                if not line:
                    break
        story += [KeepTogether([Paragraph(esc(f), FILEH),
                                Paragraph(f"{len(lines):,} lines" + (f" · {esc(purpose(f))}" if purpose(f) else ""), FILEMETA)]),
                  Preformatted("\n".join(out) if out else "(empty)", CODE)]

doc = Doc(OUT)
doc.multiBuild(story)
print(f"wrote {OUT}: {len(SOURCE)} files, {TOTAL_LINES} lines, {sum(tests.values())} Java tests, {len(rules)} rules, {RULE_TESTS} rule tests")
