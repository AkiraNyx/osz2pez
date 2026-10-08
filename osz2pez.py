"""Convert osu!mania .osz beatmaps to Phira RPE .pez packages."""

from __future__ import annotations

import argparse
import json
import ntpath
import os
import re
import shutil
import stat
import sys
import tempfile
import zipfile
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from fractions import Fraction
from pathlib import Path, PurePosixPath
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


RPE_VERSION = 160
SUPPORTED_MODE = 3
# Phira applies its own RPE speed ratio at render time. RPE 17 is the
# slightly-slower calibration point requested after the RPE 18 build.
RPE_SPEED_PER_NORMALIZED_SCROLL = Decimal("16.5")
SCROLL_SPEED_EPSILON = Decimal("0.000001")
RPE_MAX_INT32 = 2**31 - 1
RPE_MAX_UINT32 = 2**32 - 1
# ponytail: keep one calibration knob; the default maps the user's osu! speed 33 to RPE 17.
DEFAULT_RPE_SPEED_SCALE = Decimal("1.0")
DEFAULT_AUDIO_OFFSET_MS = 75
CHART_PREROLL_MS = 400
JUDGELINE_Y = -280.0


class ConversionError(ValueError):
    """A user-fixable input or conversion error."""


class UnsupportedConversion(ConversionError):
    """The input is valid but outside the supported Mania subset."""


@dataclass(frozen=True)
class TimingPoint:
    time_ms: Decimal
    beat_length_ms: Decimal
    uninherited: bool
    line_number: int


@dataclass(frozen=True)
class ManiaNote:
    column: int
    start_ms: Decimal
    end_ms: Decimal
    hold: bool
    sample_filename: str = ""


@dataclass
class ParsedBeatmap:
    path: Path
    mode: int
    title: str = ""
    title_unicode: str = ""
    artist: str = ""
    artist_unicode: str = ""
    creator: str = ""
    version: str = ""
    audio_filename: str = ""
    background_filename: str = ""
    preview_time_ms: Decimal = Decimal("-1")
    key_count: int = 0
    overall_difficulty: Decimal = Decimal("0")
    timing_points: List[TimingPoint] = field(default_factory=list)
    notes: List[ManiaNote] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class ConversionResult:
    output_path: Path
    warnings: Tuple[str, ...]


def decimal_value(value: str, label: str) -> Decimal:
    try:
        result = Decimal(value.strip())
    except (InvalidOperation, ValueError) as exc:
        raise ConversionError(f"{label} 不是有效数字: {value!r}") from exc
    if not result.is_finite():
        raise ConversionError(f"{label} 不能是无穷或 NaN: {value!r}")
    return result


def integer_value(value: str, label: str) -> int:
    number = decimal_value(value, label)
    if number != number.to_integral_value():
        raise ConversionError(f"{label} 必须是整数: {value!r}")
    return int(number)


def fraction_value(value: Decimal) -> Fraction:
    return Fraction(str(value))


def normalize_member_name(name: str) -> Optional[PurePosixPath]:
    """Return a safe relative ZIP path, or None for a directory entry."""

    normalized = name.replace("\\", "/")
    if "\x00" in normalized:
        raise ConversionError("ZIP 条目包含 NUL 字符")
    drive, _ = ntpath.splitdrive(normalized)
    if drive or normalized.startswith("/"):
        raise ConversionError(f"ZIP 条目不是相对路径: {name!r}")

    parts: List[str] = []
    for part in normalized.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            raise ConversionError(f"ZIP 条目包含路径穿越: {name!r}")
        if ":" in part:
            raise ConversionError(f"ZIP 条目包含非法路径组件: {name!r}")
        parts.append(part)

    if not parts:
        return None
    return PurePosixPath(*parts)


def ensure_within(root: Path, candidate: Path) -> None:
    root_text = os.path.normcase(str(root.resolve()))
    candidate_text = os.path.normcase(str(candidate.resolve()))
    if os.path.commonpath([root_text, candidate_text]) != root_text:
        raise ConversionError(f"路径超出允许目录: {candidate}")


def safe_extract_zip(archive_path: Path, destination: Path) -> None:
    """Extract an archive after validating every member path and type."""

    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    seen: set[str] = set()

    try:
        archive = zipfile.ZipFile(archive_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise ConversionError(f"无法打开 ZIP: {archive_path}") from exc

    with archive:
        for member in archive.infolist():
            relative = normalize_member_name(member.filename)
            if relative is None:
                continue

            key = relative.as_posix().casefold()
            if key in seen:
                raise ConversionError(
                    f"ZIP 中存在重复或大小写冲突的条目: {member.filename!r}"
                )
            seen.add(key)

            mode = (member.external_attr >> 16) & 0xFFFF
            if mode and stat.S_ISLNK(mode):
                raise ConversionError(f"ZIP 中不允许符号链接: {member.filename!r}")

            target = destination.joinpath(*relative.parts)
            ensure_within(root, target)
            if member.is_dir() or member.filename.endswith(("/", "\\")):
                target.mkdir(parents=True, exist_ok=True)
                continue

            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                with archive.open(member, "r") as source, target.open("wb") as sink:
                    shutil.copyfileobj(source, sink, length=1024 * 1024)
            except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
                raise ConversionError(
                    f"无法读取 ZIP 条目: {member.filename!r}"
                ) from exc


def discover_osu_files(root: Path) -> List[Path]:
    return sorted(
        (path for path in root.rglob("*") if path.is_file() and path.suffix.lower() == ".osu"),
        key=lambda path: path.as_posix().casefold(),
    )


def parse_sections(path: Path) -> Dict[str, List[Tuple[int, str]]]:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeError) as exc:
        raise ConversionError(f"无法读取 .osu 文件: {path}") from exc

    sections: Dict[str, List[Tuple[int, str]]] = {}
    current: Optional[str] = None
    for line_number, raw_line in enumerate(text.splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("//"):
            continue
        if line.startswith("[") and line.endswith("]"):
            current = line[1:-1].strip()
            sections.setdefault(current, [])
            continue
        if current is not None:
            sections[current].append((line_number, raw_line.rstrip()))
    return sections


def section_values(
    sections: Dict[str, List[Tuple[int, str]]], section: str
) -> Dict[str, str]:
    values: Dict[str, str] = {}
    for line_number, raw_line in sections.get(section, []):
        if ":" not in raw_line:
            continue
        key, value = raw_line.split(":", 1)
        values[key.strip()] = value.strip()
    return values


def first_quoted_value(line: str) -> str:
    match = re.search(r'"([^"]+)"', line)
    if match:
        return match.group(1)
    return ""


def parse_events(
    sections: Dict[str, List[Tuple[int, str]]],
) -> Tuple[str, List[str]]:
    background = ""
    warnings: List[str] = []
    for line_number, raw_line in sections.get("Events", []):
        line = raw_line.strip()
        lower = line.casefold()
        if lower.startswith("//storyboard") or lower.startswith("storyboard"):
            warnings.append(f"Events 第 {line_number} 行包含未转换的故事板内容")
        if lower.startswith(("1,", "video,")):
            warnings.append(f"Events 第 {line_number} 行包含未转换的视频")
            continue
        if lower.startswith(("0,", "background,")) and not background:
            background = first_quoted_value(line)
            if not background:
                fields = [field.strip().strip('"') for field in line.split(",")]
                if len(fields) >= 3:
                    background = fields[2]
    return background, warnings


def parse_timing_points(
    sections: Dict[str, List[Tuple[int, str]]],
) -> List[TimingPoint]:
    points: List[TimingPoint] = []
    for line_number, raw_line in sections.get("TimingPoints", []):
        fields = [field.strip() for field in raw_line.split(",")]
        if len(fields) < 2:
            raise ConversionError(
                f"TimingPoints 第 {line_number} 行字段不足: {raw_line!r}"
            )
        time_ms = decimal_value(fields[0], f"TimingPoints 第 {line_number} 行时间")
        beat_length = decimal_value(
            fields[1], f"TimingPoints 第 {line_number} 行 beatLength"
        )
        raw_uninherited = fields[6] if len(fields) > 6 else "1"
        uninherited = integer_value(
            raw_uninherited, f"TimingPoints 第 {line_number} 行 uninherited"
        )
        if uninherited not in (0, 1):
            raise ConversionError(
                f"TimingPoints 第 {line_number} 行 uninherited 必须为 0 或 1"
            )
        if beat_length == 0:
            raise ConversionError(f"TimingPoints 第 {line_number} 行 beatLength 不能为 0")
        if uninherited == 1 and beat_length < 0:
            raise ConversionError(
                f"TimingPoints 第 {line_number} 行红线 beatLength 不能为负数"
            )
        points.append(
            TimingPoint(
                time_ms=time_ms,
                beat_length_ms=beat_length,
                uninherited=uninherited == 1,
                line_number=line_number,
            )
        )

    points.sort(key=lambda point: (point.time_ms, point.line_number))
    if not any(point.uninherited for point in points):
        raise ConversionError("谱面没有可用的红线 TimingPoint")
    return points


def custom_sample_filename(sample_text: str) -> str:
    fields = sample_text.strip().split(":")
    if len(fields) < 5:
        return ""
    return fields[4].strip().strip('"')


def parse_hit_objects(
    sections: Dict[str, List[Tuple[int, str]]], key_count: int
) -> List[ManiaNote]:
    notes: List[ManiaNote] = []
    for line_number, raw_line in sections.get("HitObjects", []):
        fields = raw_line.split(",", 5)
        if len(fields) < 5:
            raise ConversionError(
                f"HitObjects 第 {line_number} 行字段不足: {raw_line!r}"
            )

        x = integer_value(fields[0], f"HitObjects 第 {line_number} 行 x")
        start_ms = decimal_value(
            fields[2], f"HitObjects 第 {line_number} 行时间"
        )
        object_type = integer_value(
            fields[3], f"HitObjects 第 {line_number} 行 type"
        )
        if x < 0 or x > 512:
            raise ConversionError(
                f"HitObjects 第 {line_number} 行 x 超出 0..512: {x}"
            )

        column = min(key_count - 1, max(0, (x * key_count) // 512))
        is_hold = bool(object_type & 128)
        if is_hold:
            if len(fields) < 6 or ":" not in fields[5]:
                raise ConversionError(
                    f"HitObjects 第 {line_number} 行 Hold 缺少结束时间"
                )
            end_text, sample_text = fields[5].split(":", 1)
            end_ms = decimal_value(
                end_text, f"HitObjects 第 {line_number} 行 Hold 结束时间"
            )
            if end_ms < start_ms:
                raise ConversionError(
                    f"HitObjects 第 {line_number} 行 Hold 结束时间早于开始时间"
                )
            sample_filename = custom_sample_filename(sample_text)
        else:
            if not (object_type & 1):
                raise UnsupportedConversion(
                    f"HitObjects 第 {line_number} 行不是支持的 Mania Tap/Hold: "
                    f"type={object_type}"
                )
            end_ms = start_ms
            sample_filename = (
                custom_sample_filename(fields[5]) if len(fields) > 5 else ""
            )

        notes.append(
            ManiaNote(
                column=column,
                start_ms=start_ms,
                end_ms=end_ms,
                hold=is_hold,
                sample_filename=sample_filename,
            )
        )
    return notes


def parse_osu_file(path: Path) -> ParsedBeatmap:
    sections = parse_sections(path)
    general = section_values(sections, "General")
    mode = integer_value(general.get("Mode", "0"), f"{path.name} Mode")
    if mode != SUPPORTED_MODE:
        return ParsedBeatmap(path=path, mode=mode)

    metadata = section_values(sections, "Metadata")
    difficulty = section_values(sections, "Difficulty")
    audio_filename = general.get("AudioFilename", "").strip().strip('"')
    if not audio_filename:
        raise ConversionError(f"{path.name} 缺少 General.AudioFilename")

    key_count = integer_value(
        difficulty.get("CircleSize", ""), f"{path.name} CircleSize"
    )
    if not 1 <= key_count <= 18:
        raise ConversionError(f"{path.name} CircleSize 不在 1..18 范围内: {key_count}")

    background_filename, event_warnings = parse_events(sections)
    timing_points = parse_timing_points(sections)
    notes = parse_hit_objects(sections, key_count)
    preview_time = decimal_value(
        general.get("PreviewTime", "-1"), f"{path.name} PreviewTime"
    )
    overall_difficulty = decimal_value(
        difficulty.get("OverallDifficulty", "0"),
        f"{path.name} OverallDifficulty",
    )
    return ParsedBeatmap(
        path=path,
        mode=mode,
        title=metadata.get("Title", ""),
        title_unicode=metadata.get("TitleUnicode", ""),
        artist=metadata.get("Artist", ""),
        artist_unicode=metadata.get("ArtistUnicode", ""),
        creator=metadata.get("Creator", ""),
        version=metadata.get("Version", ""),
        audio_filename=audio_filename,
        background_filename=background_filename,
        preview_time_ms=preview_time,
        key_count=key_count,
        overall_difficulty=overall_difficulty,
        timing_points=timing_points,
        notes=notes,
        warnings=event_warnings,
    )


class TimingConverter:
    def __init__(self, timing_points: Sequence[TimingPoint]):
        self._points = sorted(
            timing_points, key=lambda point: (point.time_ms, point.line_number)
        )
        green = [point for point in self._points if not point.uninherited]

        red_by_time: Dict[Decimal, TimingPoint] = {}
        for point in self._points:
            if point.uninherited:
                red_by_time[point.time_ms] = point
        red = sorted(red_by_time.values(), key=lambda point: point.time_ms)
        if not red:
            raise ConversionError("没有可用的红线 TimingPoint")
        first_red_time = red[0].time_ms
        for point in green:
            if point.time_ms < first_red_time:
                raise UnsupportedConversion(
                    f"{point.line_number} 行绿线位于第一条红线之前，无法继承 TimingPoint"
                )
            if point.beat_length_ms >= 0:
                raise ConversionError(
                    f"{point.line_number} 行绿线 beatLength 必须为负数"
                )

        self._segments: List[Tuple[Decimal, Decimal, Fraction]] = []
        cumulative = Fraction(0)
        for index, point in enumerate(red):
            if index:
                previous = red[index - 1]
                delta = fraction_value(point.time_ms - previous.time_ms)
                cumulative += delta / fraction_value(previous.beat_length_ms)
            self._segments.append(
                (point.time_ms, point.beat_length_ms, cumulative)
            )
        self._red = red

    @property
    def offset_ms(self) -> int:
        value = self._red[0].time_ms
        if value != value.to_integral_value():
            raise ConversionError(
                f"第一条红线时间必须是整数毫秒，无法写入 RPE META.offset: {value}"
            )
        offset = int(value)
        if not -RPE_MAX_INT32 <= offset <= RPE_MAX_INT32:
            raise ConversionError(f"RPE META.offset 超出 i32 范围: {offset}")
        return offset

    @property
    def preroll_beats(self) -> Fraction:
        return Fraction(CHART_PREROLL_MS) / fraction_value(
            self._red[0].beat_length_ms
        )

    def speed_events(
        self,
        max_time_ms: Decimal,
        speed_scale: object = DEFAULT_RPE_SPEED_SCALE,
    ) -> List[Dict[str, object]]:
        """Convert osu!mania's absolute scroll-speed sections to RPE events."""

        speed_scale_value = decimal_value(str(speed_scale), "speed-scale")
        if speed_scale_value <= 0:
            raise ConversionError("speed-scale 必须大于 0")
        first_time = self._red[0].time_ms
        if max_time_ms <= first_time:
            max_time_ms = first_time + self._red[0].beat_length_ms

        effective_speed_by_time: Dict[Decimal, Decimal] = {}
        current_bpm = Decimal("60000") / self._red[0].beat_length_ms
        current_sv = Decimal("1")
        for point in self._points:
            if point.time_ms < first_time:
                continue
            if point.uninherited:
                current_bpm = Decimal("60000") / point.beat_length_ms
                current_sv = Decimal("1")
            else:
                current_sv = Decimal("-100") / point.beat_length_ms
            effective_speed_by_time[point.time_ms] = current_bpm * current_sv
        effective_speed_by_time.setdefault(first_time, current_bpm * current_sv)
        changes = sorted(effective_speed_by_time.items(), key=lambda item: item[0])

        sections: List[Tuple[Decimal, Decimal, Decimal]] = []
        for index, (start_time, effective_speed) in enumerate(changes):
            if start_time >= max_time_ms:
                break
            end_time = (
                changes[index + 1][0]
                if index + 1 < len(changes)
                else max_time_ms
            )
            end_time = min(end_time, max_time_ms)
            if end_time > start_time:
                sections.append((start_time, end_time, effective_speed))
        if not sections:
            return []

        merged_sections: List[Tuple[Decimal, Decimal, Decimal]] = []
        for section in sections:
            if merged_sections:
                previous = merged_sections[-1]
                scale = max(
                    Decimal("1"), abs(previous[2]), abs(section[2])
                )
                if (
                    abs(section[2] - previous[2])
                    <= SCROLL_SPEED_EPSILON * scale
                ):
                    merged_sections[-1] = (
                        previous[0], section[1], previous[2]
                    )
                    continue
            merged_sections.append(section)
        sections = merged_sections

        # Normalize the absolute osu! scroll speed to the longest section. This
        # keeps a chart's intentional SV changes while removing arbitrary BPM
        # units from the RPE speed scale.
        reference_speed = max(
            enumerate(sections),
            key=lambda item: (
                item[1][1] - item[1][0],
                -item[0],
            ),
        )[1][2]

        result: List[Dict[str, object]] = []
        for start_time, end_time, effective_speed in sections:
            speed = float(
                effective_speed
                / reference_speed
                * RPE_SPEED_PER_NORMALIZED_SCROLL
                * speed_scale_value
            )
            result.append(
                {
                    "end": speed,
                    "endTime": self.rpe_beat(end_time),
                    "linkgroup": 0,
                    "start": speed,
                    "startTime": self.rpe_beat(start_time),
                }
            )
        if result:
            # Keep speed active during the short pre-roll before the first note.
            result[0]["startTime"] = [0, 0, 1]
        return result

    def beat_at(self, time_ms: Decimal) -> Fraction:
        selected = self._segments[0]
        for segment in self._segments:
            if time_ms < segment[0]:
                break
            selected = segment
        segment_time, beat_length, segment_beat = selected
        delta = fraction_value(time_ms - segment_time)
        return segment_beat + delta / fraction_value(beat_length)

    @staticmethod
    def rpe_time(value: Fraction) -> List[int]:
        value = value.limit_denominator(RPE_MAX_UINT32)
        integer, numerator = divmod(value.numerator, value.denominator)
        if not -RPE_MAX_INT32 <= integer <= RPE_MAX_INT32:
            raise ConversionError(f"RPE 时间整数部分超出 i32 范围: {integer}")
        return [integer, numerator, value.denominator]

    def rpe_beat(self, time_ms: Decimal) -> List[int]:
        return self.rpe_time(self.beat_at(time_ms) + self.preroll_beats)

    def bpm_list(self) -> List[Dict[str, object]]:
        first_bpm = Decimal("60000") / self._red[0].beat_length_ms
        result: List[Dict[str, object]] = [
            {"bpm": float(first_bpm), "startTime": [0, 0, 1]}
        ]
        for point in self._red:
            bpm = Decimal("60000") / point.beat_length_ms
            result.append(
                {
                    "bpm": float(bpm),
                    "startTime": self.rpe_beat(point.time_ms),
                }
            )
        return result


def safe_reference_parts(reference: str) -> PurePosixPath:
    relative = normalize_member_name(reference.strip().strip('"'))
    if relative is None:
        raise ConversionError(f"资源引用为空: {reference!r}")
    return relative


def resolve_resource(
    osu_path: Path, extracted_root: Path, reference: str
) -> Path:
    relative = safe_reference_parts(reference)
    candidate = (osu_path.parent.joinpath(*relative.parts)).resolve()
    ensure_within(extracted_root.resolve(), candidate)
    if not candidate.is_file():
        raise ConversionError(
            f"{osu_path.name} 引用的资源不存在: {reference!r}"
        )
    return candidate


def safe_output_stem(value: str, fallback: str) -> str:
    cleaned = re.sub(r'[<>:"/\\|?*]+', "_", value).strip(" .")
    return cleaned or fallback


def safe_asset_name(value: str) -> str:
    name = Path(value.replace("\\", "/")).name
    name = re.sub(r'[<>:"/\\|?*]+', "_", name).strip(" .")
    return name or "asset"


class ResourceCollector:
    def __init__(self) -> None:
        self._source_to_target: Dict[Path, str] = {}
        self._used = {"info.yml", "chart.json"}

    def add(self, source: Path) -> str:
        source = source.resolve()
        if source in self._source_to_target:
            return self._source_to_target[source]

        base = safe_asset_name(source.name)
        candidate = base
        counter = 2
        while candidate.casefold() in {item.casefold() for item in self._used}:
            source_path = Path(base)
            candidate = f"{source_path.stem}-{counter}{source_path.suffix}"
            counter += 1
        self._used.add(candidate)
        self._source_to_target[source] = candidate
        return candidate

    def items(self) -> Iterable[Tuple[str, Path]]:
        for source, target in self._source_to_target.items():
            yield target, source


def yaml_scalar(value: object) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list):
        return json.dumps(value, ensure_ascii=False)
    return json.dumps(str(value), ensure_ascii=False)


def make_info_yaml(values: Dict[str, object]) -> str:
    return "".join(
        f"{key}: {yaml_scalar(value)}\n" for key, value in values.items()
    )


def make_chart(
    beatmap: ParsedBeatmap,
    timing: TimingConverter,
    audio_target: str,
    background_target: str,
    sample_targets: Dict[str, str],
    lane_span: float = 1200.0,
    speed_scale: object = DEFAULT_RPE_SPEED_SCALE,
    audio_offset_ms: int = DEFAULT_AUDIO_OFFSET_MS,
) -> Dict[str, object]:
    notes: List[Dict[str, object]] = []
    for note in beatmap.notes:
        position_x = ((note.column + 0.5) / beatmap.key_count - 0.5) * lane_span
        item: Dict[str, object] = {
            "above": 1,
            "alpha": 255,
            "isFake": 0,
            "positionX": position_x,
            "size": 1.0,
            "speed": 1.0,
            "startTime": timing.rpe_beat(note.start_ms),
            "endTime": timing.rpe_beat(note.end_ms),
            "type": 2 if note.hold else 1,
            "visibleTime": 999999.0,
            "yOffset": 0.0,
        }
        if note.sample_filename and not note.hold:
            item["hitsound"] = sample_targets[note.sample_filename]
        notes.append(item)

    max_time_ms = max(
        (note.end_ms for note in beatmap.notes),
        default=timing._red[0].time_ms,
    )
    speed_events = timing.speed_events(max_time_ms, speed_scale)
    move_y_events = [
        {
            "bezier": 0,
            "bezierPoints": [0.0, 0.0, 0.0, 0.0],
            "easingLeft": 0.0,
            "easingRight": 1.0,
            "easingType": 1,
            "end": JUDGELINE_Y,
            "endTime": timing.rpe_beat(max_time_ms),
            "linkgroup": 0,
            "start": JUDGELINE_Y,
            "startTime": [0, 0, 1],
        }
    ]
    alpha_events = [
        {
            "bezier": 0,
            "bezierPoints": [0.0, 0.0, 0.0, 0.0],
            "easingLeft": 0.0,
            "easingRight": 1.0,
            "easingType": 1,
            "end": 255,
            "endTime": timing.rpe_beat(max_time_ms),
            "linkgroup": 0,
            "start": 255,
            "startTime": [0, 0, 1],
        }
    ]
    offset_ms = timing.offset_ms + int(audio_offset_ms) - CHART_PREROLL_MS
    if not -RPE_MAX_INT32 <= offset_ms <= RPE_MAX_INT32:
        raise ConversionError(f"RPE META.offset 超出 i32 范围: {offset_ms}")

    return {
        "BPMList": timing.bpm_list(),
        "META": {
            "RPEVersion": RPE_VERSION,
            "background": background_target,
            "song": audio_target,
            "name": beatmap.title_unicode or beatmap.title or beatmap.path.stem,
            "level": beatmap.version or f"{beatmap.key_count}K",
            "composer": beatmap.artist_unicode or beatmap.artist,
            "charter": beatmap.creator,
            "offset": offset_ms,
        },
        "judgeLineList": [
            {
                "Name": f"{beatmap.key_count}K",
                "Texture": "line.png",
                "father": -1,
                "isCover": 1,
                "bpmfactor": 1.0,
                "notes": notes,
                "numOfNotes": sum(1 for note in beatmap.notes if not note.hold),
                "eventLayers": [
                    {
                        "alphaEvents": alpha_events,
                        "moveYEvents": move_y_events,
                        "speedEvents": speed_events,
                    }
                ],
            }
        ],
    }


def make_info(
    beatmap: ParsedBeatmap,
    audio_target: str,
    background_target: str,
) -> Dict[str, object]:
    preview_start = (
        float(max(Decimal("0"), beatmap.preview_time_ms)) / 1000.0
    )
    name = beatmap.title_unicode or beatmap.title or beatmap.path.stem
    level = beatmap.version or f"{beatmap.key_count}K"
    return {
        "name": name,
        "difficulty": float(beatmap.overall_difficulty),
        "level": level,
        "charter": beatmap.creator,
        "composer": beatmap.artist_unicode or beatmap.artist,
        "illustrator": "",
        "chart": "chart.json",
        "music": audio_target,
        "illustration": background_target,
        "previewStart": preview_start,
        "previewEnd": preview_start + 15.0,
        "aspectRatio": 1.7777778,
        "backgroundDim": 0.6,
        "lineLength": 6.0,
        "offset": 0.0,
        "tags": [],
        "intro": "",
        "holdPartialCover": False,
    }


def validate_rpe_time(value: object, label: str) -> None:
    if (
        not isinstance(value, list)
        or len(value) != 3
        or not all(isinstance(item, int) and not isinstance(item, bool) for item in value)
    ):
        raise ConversionError(f"{label} 不是 int[3] 时间三元组")
    integer, numerator, denominator = value
    if not -RPE_MAX_INT32 <= integer <= RPE_MAX_INT32:
        raise ConversionError(f"{label} 整数部分超出 i32 范围: {integer}")
    if not 0 <= numerator <= RPE_MAX_UINT32:
        raise ConversionError(f"{label} 分子超出 u32 范围: {numerator}")
    if not 1 <= denominator <= RPE_MAX_UINT32:
        raise ConversionError(f"{label} 分母超出 u32 范围: {denominator}")


def validate_chart_times(chart: Dict[str, object]) -> None:
    for index, event in enumerate(chart.get("BPMList", [])):
        validate_rpe_time(event["startTime"], f"BPMList[{index}].startTime")
    for line_index, line in enumerate(chart.get("judgeLineList", [])):
        for key in ("Name", "Texture", "father", "isCover", "eventLayers", "notes"):
            if key not in line:
                raise ConversionError(
                    f"judgeLineList[{line_index}] 缺少必填字段: {key}"
                )
        for layer_index, layer in enumerate(line.get("eventLayers", [])):
            for event_type in (
                "speedEvents", "moveXEvents", "moveYEvents",
                "rotateEvents", "alphaEvents",
            ):
                for event_index, event in enumerate(layer.get(event_type, [])):
                    prefix = (
                        f"judgeLineList[{line_index}].eventLayers[{layer_index}]"
                        f".{event_type}[{event_index}]"
                    )
                    validate_rpe_time(event["startTime"], f"{prefix}.startTime")
                    validate_rpe_time(event["endTime"], f"{prefix}.endTime")
        for note_index, note in enumerate(line.get("notes", [])):
            prefix = f"judgeLineList[{line_index}].notes[{note_index}]"
            validate_rpe_time(note["startTime"], f"{prefix}.startTime")
            validate_rpe_time(note["endTime"], f"{prefix}.endTime")


def validate_chart_shape(chart: Dict[str, object]) -> None:
    for key in ("BPMList", "META", "judgeLineList"):
        if key not in chart:
            raise ConversionError(f"RPE 根对象缺少必填字段: {key}")
    for index, event in enumerate(chart["BPMList"]):
        for key in ("bpm", "startTime"):
            if key not in event:
                raise ConversionError(f"BPMList[{index}] 缺少必填字段: {key}")
    for line_index, line in enumerate(chart["judgeLineList"]):
        for key in ("Name", "Texture", "father", "isCover", "eventLayers", "notes"):
            if key not in line:
                raise ConversionError(
                    f"judgeLineList[{line_index}] 缺少必填字段: {key}"
                )
        for layer_index, layer in enumerate(line["eventLayers"]):
            for event_type in (
                "speedEvents", "moveXEvents", "moveYEvents",
                "rotateEvents", "alphaEvents",
            ):
                for event_index, event in enumerate(layer.get(event_type, [])):
                    prefix = (
                        f"judgeLineList[{line_index}].eventLayers[{layer_index}]"
                        f".{event_type}[{event_index}]"
                    )
                    for key in ("start", "end", "startTime", "endTime"):
                        if key not in event:
                            raise ConversionError(f"{prefix} 缺少必填字段: {key}")
        for note_index, note in enumerate(line["notes"]):
            prefix = f"judgeLineList[{line_index}].notes[{note_index}]"
            for key in (
                "type", "above", "startTime", "endTime", "positionX",
                "yOffset", "alpha", "size", "speed", "isFake", "visibleTime",
            ):
                if key not in note:
                    raise ConversionError(f"{prefix} 缺少必填字段: {key}")


def verify_package(
    package_path: Path, info_values: Dict[str, object], chart: Dict[str, object]
) -> None:
    try:
        archive = zipfile.ZipFile(package_path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise ConversionError(f"生成的 .pez 不是有效 ZIP: {package_path}") from exc

    with archive:
        bad_member = archive.testzip()
        if bad_member is not None:
            raise ConversionError(f"生成的 .pez CRC 校验失败: {bad_member}")
        names = set(archive.namelist())
        required = {"info.yml", "chart.json"}
        if not required.issubset(names):
            raise ConversionError("生成的 .pez 缺少 info.yml 或 chart.json")
        if any(
            name.startswith(("/", "\\")) or ".." in PurePosixPath(name).parts
            for name in names
        ):
            raise ConversionError("生成的 .pez 包含不安全路径")
        loaded_chart = json.loads(archive.read("chart.json").decode("utf-8"))
        validate_chart_shape(loaded_chart)
        for line_index, line in enumerate(loaded_chart["judgeLineList"]):
            texture = line["Texture"]
            if texture != "line.png" and texture not in names:
                raise ConversionError(
                    f"judgeLineList[{line_index}].Texture 未指向包内文件: {texture}"
                )
        validate_chart_times(loaded_chart)
        if loaded_chart != chart:
            raise ConversionError("重新读取的 chart.json 与生成内容不一致")
        info_text = archive.read("info.yml").decode("utf-8")
        for key in ("chart", "music", "illustration"):
            expected = str(info_values[key])
            match = re.search(rf"(?m)^{re.escape(key)}:\s*(.+)$", info_text)
            if not match:
                raise ConversionError(f"info.yml 缺少字段: {key}")
            actual = json.loads(match.group(1))
            if actual != expected or actual not in names:
                raise ConversionError(
                    f"info.yml.{key} 未指向包内文件: {expected}"
                )


def write_package(
    package_path: Path,
    info_values: Dict[str, object],
    chart: Dict[str, object],
    resources: ResourceCollector,
) -> None:
    if package_path.exists():
        raise ConversionError(f"输出文件已存在，不覆盖: {package_path}")
    package_path.parent.mkdir(parents=True, exist_ok=True)
    info_text = make_info_yaml(info_values)
    chart_text = json.dumps(chart, ensure_ascii=False, indent=2) + "\n"
    created = False
    try:
        with package_path.open("xb") as raw_file:
            created = True
            with zipfile.ZipFile(
                raw_file, mode="w", compression=zipfile.ZIP_DEFLATED
            ) as archive:
                archive.writestr("info.yml", info_text.encode("utf-8"))
                archive.writestr("chart.json", chart_text.encode("utf-8"))
                for target, source in resources.items():
                    archive.write(source, target)
        verify_package(package_path, info_values, chart)
    except ConversionError:
        if created and package_path.exists():
            package_path.unlink()
        raise
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        if created and package_path.exists():
            package_path.unlink()
        raise ConversionError(f"无法写入或验证 .pez: {package_path}") from exc


def convert_one(
    beatmap: ParsedBeatmap,
    extracted_root: Path,
    output_dir: Path,
    osb_present: bool,
    speed_scale: object = DEFAULT_RPE_SPEED_SCALE,
    audio_offset_ms: int = DEFAULT_AUDIO_OFFSET_MS,
) -> ConversionResult:
    if beatmap.mode != SUPPORTED_MODE:
        raise ConversionError(f"内部错误：尝试转换非 Mania 文件 {beatmap.path}")
    timing = TimingConverter(beatmap.timing_points)
    resources = ResourceCollector()

    audio_source = resolve_resource(
        beatmap.path, extracted_root, beatmap.audio_filename
    )
    audio_target = resources.add(audio_source)
    if not beatmap.background_filename:
        raise ConversionError(f"{beatmap.path.name} 没有可用的背景图")
    background_source = resolve_resource(
        beatmap.path, extracted_root, beatmap.background_filename
    )
    background_target = resources.add(background_source)

    sample_targets: Dict[str, str] = {}
    warnings = list(beatmap.warnings)
    for note in beatmap.notes:
        if not note.sample_filename:
            continue
        if note.hold:
            warnings.append(
                f"{beatmap.path.name}: Hold 的自定义 hitSample 无法由 RPE Hold 表达，已省略"
            )
            continue
        sample_source = resolve_resource(
            beatmap.path, extracted_root, note.sample_filename
        )
        sample_targets[note.sample_filename] = resources.add(sample_source)
    if osb_present:
        warnings.append(
            f"{beatmap.path.name}: 检测到外部 .osb，故事板未转换"
        )

    chart = make_chart(
        beatmap,
        timing,
        audio_target,
        background_target,
        sample_targets,
        speed_scale=speed_scale,
        audio_offset_ms=audio_offset_ms,
    )
    info_values = make_info(beatmap, audio_target, background_target)
    title = beatmap.title_unicode or beatmap.title or beatmap.path.stem
    level = beatmap.version or f"{beatmap.key_count}K"
    stem = safe_output_stem(f"{title} - {level}", beatmap.path.stem)
    package_path = output_dir / f"{stem}.pez"
    write_package(package_path, info_values, chart, resources)
    return ConversionResult(package_path, tuple(warnings))


def convert_osz(
    input_path: Path,
    output_dir: Path,
    speed_scale: object = DEFAULT_RPE_SPEED_SCALE,
    audio_offset_ms: int = DEFAULT_AUDIO_OFFSET_MS,
) -> List[ConversionResult]:
    input_path = input_path.expanduser().resolve()
    if not input_path.is_file():
        raise ConversionError(f"输入文件不存在: {input_path}")
    if input_path.suffix.casefold() != ".osz":
        raise ConversionError(f"输入文件扩展名不是 .osz: {input_path}")

    output_dir = output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    results: List[ConversionResult] = []
    created: List[Path] = []
    try:
        with tempfile.TemporaryDirectory(prefix="osz2pez-") as temp_dir:
            extracted_root = Path(temp_dir)
            safe_extract_zip(input_path, extracted_root)
            osu_files = discover_osu_files(extracted_root)
            if not osu_files:
                raise ConversionError("输入 .osz 中没有 .osu 文件")
            osb_present = any(
                path.is_file() and path.suffix.casefold() == ".osb"
                for path in extracted_root.rglob("*")
            )
            for osu_path in osu_files:
                beatmap = parse_osu_file(osu_path)
                if beatmap.mode != SUPPORTED_MODE:
                    continue
                result = convert_one(
                    beatmap,
                    extracted_root,
                    output_dir,
                    osb_present,
                    speed_scale,
                    audio_offset_ms,
                )
                results.append(result)
                created.append(result.output_path)
    except Exception:
        for path in created:
            try:
                path.unlink()
            except OSError:
                pass
        raise

    if not results:
        raise ConversionError("输入 .osz 中没有 Mode: 3 的 osu!mania 难度")
    return results


def build_test_archive(path: Path) -> None:
    osu_text = """osu file format v14

[General]
AudioFilename: song.ogg
PreviewTime: 1000
Mode: 3

[Metadata]
Title: Demo
TitleUnicode: Demo Unicode
Artist: Artist
ArtistUnicode: Artist Unicode
Creator: Mapper
Version: 4K

[Difficulty]
CircleSize: 4
OverallDifficulty: 7

[Events]
0,0,"background.png",0,0

[TimingPoints]
100,500,4,1,0,100,1,0
100,-100,4,1,0,100,0,0
2000,500,4,1,0,100,1,0
2000,-50,4,1,0,100,0,0

[HitObjects]
64,192,1000,1,0,0:0:0:0:clap.wav
192,192,1500,1,0
320,192,1500,1,0
448,192,2000,128,0,3500:0:0:0:
"""
    standard_text = """osu file format v14

[General]
Mode: 0
"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("charts/song.osu", osu_text.encode("utf-8"))
        archive.writestr("charts/standard.osu", standard_text.encode("utf-8"))
        archive.writestr("charts/song.ogg", b"audio")
        archive.writestr("charts/background.png", b"image")
        archive.writestr("charts/clap.wav", b"sample")


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="osz2pez-self-test-") as temp_dir:
        root = Path(temp_dir)
        input_path = root / "demo.osz"
        output_dir = root / "output"
        build_test_archive(input_path)
        results = convert_osz(input_path, output_dir)
        assert len(results) == 1
        package = results[0].output_path
        assert package.name == "Demo Unicode - 4K.pez"

        with zipfile.ZipFile(package) as archive:
            names = set(archive.namelist())
            assert {
                "info.yml",
                "chart.json",
                "song.ogg",
                "background.png",
                "clap.wav",
            } == names
            chart = json.loads(archive.read("chart.json"))
            line = chart["judgeLineList"][0]
            notes = chart["judgeLineList"][0]["notes"]
            speed_events = chart["judgeLineList"][0]["eventLayers"][0]["speedEvents"]
            move_y_events = chart["judgeLineList"][0]["eventLayers"][0]["moveYEvents"]
            alpha_events = chart["judgeLineList"][0]["eventLayers"][0]["alphaEvents"]
            assert line["Name"] == "4K"
            assert line["Texture"] == "line.png"
            assert chart["META"]["offset"] == -225
            assert move_y_events[0]["start"] == move_y_events[0]["end"] == JUDGELINE_Y
            assert len(notes) == 4
            assert len(chart["BPMList"]) == 3
            assert [event["start"] for event in speed_events] == [16.5, 33.0]
            assert notes[0]["startTime"] == [2, 3, 5]
            assert TimingConverter.rpe_time(Fraction(1, 10**15))[2] <= RPE_MAX_UINT32
            assert len(alpha_events) == 1
            assert alpha_events[0]["start"] == alpha_events[0]["end"] == 255
            assert notes[1]["startTime"] == notes[2]["startTime"]
            assert notes[3]["type"] == 2
            assert notes[3]["endTime"] != notes[3]["startTime"]
            assert notes[0]["hitsound"] == "clap.wav"

        bad_archive = root / "bad.osz"
        with zipfile.ZipFile(bad_archive, "w") as archive:
            archive.writestr("../escape.txt", b"bad")
        bad_extract = root / "bad-extract"
        try:
            safe_extract_zip(bad_archive, bad_extract)
        except ConversionError:
            pass
        else:
            raise AssertionError("路径穿越 ZIP 没有被拒绝")
        assert not (root / "escape.txt").exists()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Convert osu!mania .osz files to Phira RPE .pez packages."
    )
    parser.add_argument("input", nargs="?", help="输入 .osz 文件")
    parser.add_argument(
        "--output",
        "-o",
        default="output",
        help="输出目录，默认 output",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="运行标准库自检，不需要输入文件",
    )
    parser.add_argument(
        "--speed-scale",
        type=float,
        default=float(DEFAULT_RPE_SPEED_SCALE),
        help="RPE 滚速校准倍数，默认 1.0（osu! 参考速度 33 对应 RPE 17）",
    )
    parser.add_argument(
        "--audio-offset-ms",
        type=int,
        default=DEFAULT_AUDIO_OFFSET_MS,
        help="额外音频同步偏移（毫秒），默认 +50；正数让物件更晚出现",
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        try:
            self_test()
        except Exception as exc:
            print(f"self-test failed: {exc}", file=sys.stderr)
            return 1
        print("self-test passed")
        return 0
    if not args.input:
        parser.error("需要输入 .osz 文件，或使用 --self-test")
    try:
        results = convert_osz(
            Path(args.input),
            Path(args.output),
            speed_scale=args.speed_scale,
            audio_offset_ms=args.audio_offset_ms,
        )
    except (ConversionError, OSError, zipfile.BadZipFile) as exc:
        print(f"转换失败: {exc}", file=sys.stderr)
        return 2
    for result in results:
        print(result.output_path)
        for warning in result.warnings:
            print(f"warning: {warning}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
