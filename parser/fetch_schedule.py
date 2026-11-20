#!/usr/bin/env python3
"""Serverless schedule parser for МОУ СОШ №12 им. В.Ф. Суханова (nsportal.ru)."""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urljoin, urlparse
from zoneinfo import ZoneInfo

try:
    import requests
except ImportError:
    requests = None

try:
    from bs4 import BeautifulSoup
except ImportError:
    BeautifulSoup = None

try:
    import pdfplumber
except ImportError:
    pdfplumber = None

try:
    import fitz  # PyMuPDF fallback
except ImportError:
    fitz = None

SOURCE_PAGE_URL = "https://nsportal.ru/user/1608303/page/izmenenie-raspisaniya"
SCHOOL_NAME = "МОУ СОШ №12 им. В.Ф. Суханова"
TIMEZONE_NAME = "Europe/Saratov"
DEFAULT_CLASS = "11б (ТЕХ)"

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)

WEEKDAYS_RU = [
    "Понедельник",
    "Вторник",
    "Среда",
    "Четверг",
    "Пятница",
    "Суббота",
    "Воскресенье",
]

DATE_PATTERN = re.compile(r"\b(\d{1,2})\.(\d{1,2})\b")
TIME_RANGE_PATTERN = re.compile(r"(\d{1,2})\s*[:.]\s*(\d{2})\s*[-–—]\s*(\d{1,2})\s*[:.]\s*(\d{2})")
ROOM_TAIL_PATTERN = re.compile(
    r"(?:\s+)((?:с/з|а/з|библ\.?|акт\.?\s*зал|спорт\.?\s*зал|\d{1,3}[а-яА-Яa-zA-Z]?(?:\s*[,/]\s*\d{1,3}[а-яА-Яa-zA-Z]?)*))$",
    re.IGNORECASE,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("schedule_parser")


@dataclass
class CandidatePdf:
    url: str
    label: str
    schedule_date: date


def load_bells(bells_path: Path) -> dict[str, dict[str, str]]:
    with bells_path.open("r", encoding="utf-8") as f:
        return json.load(f)


def normalize_whitespace(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def normalize_class_name(raw: str) -> str:
    cleaned = normalize_whitespace(raw.replace("\n", " "))
    cleaned = re.sub(r"\s*\(\s*", " (", cleaned)
    cleaned = re.sub(r"\s*\)", ")", cleaned)
    return cleaned.strip()


def resolve_schedule_date(day: int, month: int, today: date) -> date | None:
    """Resolve DD.MM to a full date, handling December/January year rollover."""
    candidates: list[date] = []
    for year in (today.year - 1, today.year, today.year + 1):
        try:
            candidates.append(date(year, month, day))
        except ValueError:
            continue
    if not candidates:
        return None
    return min(candidates, key=lambda d: abs((d - today).days))


def extract_pdf_url_from_href(href: str, base_url: str) -> str | None:
    if not href:
        return None
    full_url = urljoin(base_url, href.strip())
    parsed = urlparse(full_url)

    if "docs.yandex" in parsed.netloc or "docs.google" in parsed.netloc:
        qs = parse_qs(parsed.query)
        for key in ("url", "u", "src"):
            if key in qs and qs[key]:
                inner = unquote(qs[key][0]).strip()
                if inner.lower().endswith(".pdf") or ".pdf?" in inner.lower():
                    return inner

    decoded_path = unquote(parsed.path).lower()
    if decoded_path.endswith(".pdf"):
        return full_url
    return None


def is_teacher_schedule(label: str, url: str) -> bool:
    combined = f"{label} {unquote(url)}".lower()
    return "учител" in combined or "uchitel" in combined


def extract_date_from_text(label: str, url: str, today: date) -> date | None:
    for source in (label, unquote(urlparse(url).path)):
        match = DATE_PATTERN.search(source)
        if match:
            day = int(match.group(1))
            month = int(match.group(2))
            if 1 <= day <= 31 and 1 <= month <= 12:
                resolved = resolve_schedule_date(day, month, today)
                if resolved is not None:
                    return resolved
    return None


def discover_student_pdfs(html: str, base_url: str, today: date) -> list[CandidatePdf]:
    if BeautifulSoup is None:
        raise RuntimeError("beautifulsoup4 is required for HTML parsing")

    soup = BeautifulSoup(html, "html.parser")
    discovered: dict[str, CandidatePdf] = {}

    for a_tag in soup.find_all("a", href=True):
        pdf_url = extract_pdf_url_from_href(a_tag["href"], base_url)
        if not pdf_url:
            continue
        parts = [a_tag.get_text(" ", strip=True), a_tag.get("title", "")]
        parent = a_tag.parent
        if parent is not None:
            parent_text = normalize_whitespace(parent.get_text(" ", strip=True))
            if len(parent_text) < 220:
                parts.append(parent_text)
        label = normalize_whitespace(" ".join(p for p in parts if p))

        if is_teacher_schedule(label, pdf_url):
            logger.info("Skipping teacher schedule: %s (%s)", label, pdf_url)
            continue

        sched_date = extract_date_from_text(label, pdf_url, today)
        if sched_date is None:
            logger.warning("Could not extract DD.MM date from link: %s (%s)", label, pdf_url)
            continue

        discovered[pdf_url] = CandidatePdf(url=pdf_url, label=label, schedule_date=sched_date)

    for iframe in soup.find_all("iframe", src=True):
        pdf_url = extract_pdf_url_from_href(iframe["src"], base_url)
        if not pdf_url or pdf_url in discovered:
            continue
        label = normalize_whitespace(iframe.get("title", "") or Path(unquote(urlparse(pdf_url).path)).name)
        if is_teacher_schedule(label, pdf_url):
            continue
        sched_date = extract_date_from_text(label, pdf_url, today)
        if sched_date is not None:
            discovered[pdf_url] = CandidatePdf(url=pdf_url, label=label, schedule_date=sched_date)

    by_date: dict[date, CandidatePdf] = {}
    for cand in discovered.values():
        existing = by_date.get(cand.schedule_date)
        if existing is None or ("nsportal.ru" in cand.url and "nsportal.ru" not in existing.url):
            by_date[cand.schedule_date] = cand

    all_sorted = sorted(by_date.values(), key=lambda c: c.schedule_date)
    if not all_sorted:
        return []

    relevant = [c for c in all_sorted if c.schedule_date >= today]
    if not relevant:
        relevant = all_sorted[-2:]
    elif len(relevant) == 1 and len(all_sorted) >= 2:
        prev = [c for c in all_sorted if c.schedule_date < today]
        if prev and (today - prev[-1].schedule_date) <= timedelta(days=1):
            relevant = [prev[-1], relevant[0]]

    return sorted(relevant, key=lambda c: c.schedule_date)


def parse_time_range(raw_time: str | None, lesson_number: int, bells: dict[str, dict[str, str]]) -> tuple[str, str]:
    if raw_time:
        match = TIME_RANGE_PATTERN.search(raw_time)
        if match:
            sh, sm, eh, em = match.groups()
            return f"{int(sh):02d}:{sm}", f"{int(eh):02d}:{em}"
    bell = bells.get(str(lesson_number))
    if bell:
        return bell["start"], bell["end"]
    return "", ""


def split_subject_and_room(cell_text: str) -> tuple[str, str]:
    text = normalize_whitespace(cell_text.replace("\n", " "))
    text = text.lstrip("'’`\" ").strip()
    if not text or text in ("-", "—", "–"):
        return "", ""

    match = ROOM_TAIL_PATTERN.search(text)
    if match:
        room = normalize_whitespace(match.group(1)).replace(" ,", ",").replace(", ", ",")
        subject = text[: match.start()].strip()
        if subject:
            return subject, room

    return text, ""


def resolve_merged_row_cells(row: list[str | None], valid_class_indices: list[int]) -> dict[int, str]:
    """Handle horizontal merged cells (e.g., ['Классный час Классный час', None])."""
    resolved: dict[int, str] = {}
    for pos, col_idx in enumerate(valid_class_indices):
        raw = row[col_idx] if col_idx < len(row) else None
        if raw is not None:
            resolved[col_idx] = raw
            continue

        if pos > 0:
            prev_col = valid_class_indices[pos - 1]
            prev_val = normalize_whitespace((resolved.get(prev_col) or "").replace("\n", " "))
            if prev_val:
                words = prev_val.split(" ")
                if len(words) >= 2 and len(words) % 2 == 0:
                    half = len(words) // 2
                    left_half = " ".join(words[:half])
                    right_half = " ".join(words[half:])
                    if left_half.lower() == right_half.lower():
                        resolved[prev_col] = left_half
                        resolved[col_idx] = right_half
                        continue
                resolved[col_idx] = prev_val
            else:
                resolved[col_idx] = ""
        else:
            resolved[col_idx] = ""
    return resolved


def parse_tables_into_classes(
    tables: list[list[list[str | None]]],
    bells: dict[str, dict[str, str]],
) -> dict[str, list[dict[str, Any]]]:
    classes: dict[str, list[dict[str, Any]]] = {}

    for table in tables:
        current_headers: dict[int, str] = {}
        for row in table:
            if not row or len(row) < 3:
                continue

            col0 = normalize_whitespace(str(row[0] or ""))
            col1 = normalize_whitespace(str(row[1] or ""))

            if col0 in ("№", "N", "#") or col1.lower().startswith("время"):
                current_headers = {}
                for idx in range(2, len(row)):
                    cell = row[idx]
                    if cell is not None and normalize_whitespace(str(cell)):
                        class_name = normalize_class_name(str(cell))
                        current_headers[idx] = class_name
                        classes.setdefault(class_name, [])
                continue

            if not current_headers or not re.fullmatch(r"\d{1,2}", col0):
                continue

            lesson_num = int(col0)
            start_time, end_time = parse_time_range(col1, lesson_num, bells)
            valid_indices = sorted(current_headers.keys())
            resolved_cells = resolve_merged_row_cells(row, valid_indices)

            for col_idx in valid_indices:
                class_name = current_headers[col_idx]
                cell_text = resolved_cells.get(col_idx, "")
                subject, room = split_subject_and_room(cell_text)
                if not subject:
                    continue

                classes[class_name].append(
                    {
                        "number": lesson_num,
                        "subject": subject,
                        "teacher": "",
                        "room": room,
                        "startTime": start_time,
                        "endTime": end_time,
                    }
                )

    cleaned: dict[str, list[dict[str, Any]]] = {}
    for cls_name, lessons in classes.items():
        if lessons:
            cleaned[cls_name] = sorted(lessons, key=lambda item: item["number"])
    return cleaned


def extract_tables_pdfplumber(pdf_path: Path) -> list[list[list[str | None]]]:
    if pdfplumber is None:
        return []
    all_tables: list[list[list[str | None]]] = []
    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            tables = page.extract_tables() or []
            all_tables.extend(tables)
    return all_tables


def extract_tables_pymupdf(pdf_path: Path) -> list[list[list[str | None]]]:
    if fitz is None:
        return []
    all_tables: list[list[list[str | None]]] = []
    doc = fitz.open(pdf_path)
    try:
        for page in doc:
            finder = page.find_tables()
            for tab in finder.tables:
                all_tables.append(tab.extract())
    finally:
        doc.close()
    return all_tables


def parse_pdf_schedule(pdf_path: Path, bells: dict[str, dict[str, str]]) -> dict[str, list[dict[str, Any]]]:
    tables = extract_tables_pdfplumber(pdf_path)
    classes = parse_tables_into_classes(tables, bells)
    if classes:
        return classes

    logger.warning("pdfplumber returned no classes for %s, trying PyMuPDF fallback", pdf_path.name)
    tables_fallback = extract_tables_pymupdf(pdf_path)
    return parse_tables_into_classes(tables_fallback, bells)


def download_file(session: Any, url: str, dest: Path) -> None:
    resp = session.get(url, timeout=30, allow_redirects=True)
    resp.raise_for_status()
    dest.write_bytes(resp.content)


def build_output_payload(
    days_map: dict[str, dict[str, Any]],
    bells: dict[str, dict[str, str]],
    now_local: datetime,
) -> dict[str, Any]:
    return {
        "school": SCHOOL_NAME,
        "timezone": TIMEZONE_NAME,
        "updatedAt": now_local.isoformat(timespec="seconds"),
        "defaultClass": DEFAULT_CLASS,
        "bells": bells,
        "days": dict(sorted(days_map.items())),
    }


def save_json_atomic(output_path: Path, payload: dict[str, Any]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = output_path.with_suffix(".json.tmp")
    with tmp_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp_path, output_path)


def run_remote_fetch(bells: dict[str, dict[str, str]], output_path: Path, now_local: datetime) -> int:
    if requests is None:
        logger.error("requests library is not installed")
        return 1

    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.5",
        }
    )

    try:
        response = session.get(SOURCE_PAGE_URL, timeout=30, allow_redirects=True)
        response.raise_for_status()
    except Exception as exc:
        logger.error("Network error fetching %s: %s (preserving existing schedule.json)", SOURCE_PAGE_URL, exc)
        return 0 if output_path.exists() else 1

    candidates = discover_student_pdfs(response.text, response.url, now_local.date())
    if not candidates:
        logger.warning("No student PDF schedules found on page; preserving existing schedule.json")
        return 0 if output_path.exists() else 1

    days_map: dict[str, dict[str, Any]] = {}
    with tempfile.TemporaryDirectory(prefix="school12_") as tmp_dir:
        for cand in candidates:
            pdf_file = Path(tmp_dir) / f"schedule_{cand.schedule_date.isoformat()}.pdf"
            try:
                logger.info("Downloading %s -> %s", cand.url, cand.schedule_date.isoformat())
                download_file(session, cand.url, pdf_file)
                classes = parse_pdf_schedule(pdf_file, bells)
                if not classes:
                    logger.warning("Parsed 0 classes from %s", cand.url)
                    continue
                iso_date = cand.schedule_date.isoformat()
                days_map[iso_date] = {
                    "dayOfWeek": WEEKDAYS_RU[cand.schedule_date.weekday()],
                    "sourceUrl": cand.url,
                    "classes": classes,
                }
                logger.info("Parsed %d classes for %s", len(classes), iso_date)
            except Exception as exc:
                logger.error("Failed to process %s: %s", cand.url, exc)

    if not days_map:
        logger.warning("No valid schedule data extracted; preserving existing schedule.json")
        return 0 if output_path.exists() else 1

    payload = build_output_payload(days_map, bells, now_local)
    save_json_atomic(output_path, payload)
    logger.info("Saved updated schedule to %s (%d days)", output_path, len(days_map))
    return 0


def run_local_pdf(
    pdf_path: Path,
    sched_date: date,
    bells: dict[str, dict[str, str]],
    output_path: Path,
    now_local: datetime,
) -> int:
    classes = parse_pdf_schedule(pdf_path, bells)
    if not classes:
        logger.error("No classes parsed from local PDF %s", pdf_path)
        return 1

    iso_date = sched_date.isoformat()
    days_map = {
        iso_date: {
            "dayOfWeek": WEEKDAYS_RU[sched_date.weekday()],
            "sourceUrl": SOURCE_PAGE_URL,
            "classes": classes,
        }
    }
    payload = build_output_payload(days_map, bells, now_local)
    save_json_atomic(output_path, payload)
    logger.info("Parsed %d classes from local PDF into %s", len(classes), output_path)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch and parse МОУ СОШ №12 schedule PDFs")
    parser.add_argument(
        "--bells",
        type=Path,
        default=Path(__file__).resolve().parent / "bells.json",
        help="Path to bells.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "docs" / "schedule.json",
        help="Output path for schedule.json",
    )
    parser.add_argument(
        "--local-pdf",
        type=Path,
        default=None,
        help="Parse a local PDF file instead of fetching from nsportal.ru",
    )
    parser.add_argument(
        "--date",
        type=str,
        default=None,
        help="Schedule date YYYY-MM-DD when using --local-pdf",
    )
    args = parser.parse_args()

    tz = ZoneInfo(TIMEZONE_NAME)
    now_local = datetime.now(tz)
    bells = load_bells(args.bells)

    if args.local_pdf is not None:
        sched_date = date.fromisoformat(args.date) if args.date else now_local.date()
        return run_local_pdf(args.local_pdf, sched_date, bells, args.output, now_local)

    return run_remote_fetch(bells, args.output, now_local)


if __name__ == "__main__":
    sys.exit(main())
