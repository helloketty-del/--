#!/usr/bin/env python3
"""Import the local Excel question bank into a deterministic JSON artifact.

The importer intentionally uses only the Python standard library so a fresh
checkout can regenerate the bank without an additional spreadsheet package.
"""

from __future__ import annotations

import argparse
import json
import posixpath
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET


NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_NS = "{http://schemas.openxmlformats.org/package/2006/relationships}"
RID = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
ANCHOR_LENGTH = 6
MAX_ANCHORS = 3


def text_of(node: ET.Element | None) -> str:
    return "".join(node.itertext()) if node is not None else ""


def column_index(cell_ref: str) -> int:
    value = 0
    for char in "".join(char for char in cell_ref if char.isalpha()):
        value = value * 26 + ord(char.upper()) - 64
    return value


def resolve_target(target: str) -> str:
    target = target.lstrip("/")
    return target if target.startswith("xl/") else posixpath.normpath(posixpath.join("xl", target))


def normalize_question(value: object) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return "".join(char for char in text if char.isalnum() or "\u3400" <= char <= "\u9fff")


def normalize_header(value: object) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return re.sub(r"[\s\u3000()（）\[\]【】,，。．、:：;；.!！?？“”\"'\-_]+", "", text)


def header_role(value: object) -> str | None:
    normalized = normalize_header(value)
    if normalized in {"题干", "问题", "题目", "试题"}:
        return "question"
    if normalized in {"答案", "正确答案", "正确选项"}:
        return "answer"
    if normalized in {"题型", "类型"}:
        return "type"
    if normalized in {"来源", "出处"}:
        return "source"
    if normalized in {"序号", "编号"}:
        return "serial"
    match = re.fullmatch(r"(?:选项)?([a-f])", normalized, re.IGNORECASE)
    if match:
        return f"option_{match.group(1).upper()}"
    return None


def load_shared_strings(archive: ZipFile) -> list[str]:
    name = "xl/sharedStrings.xml"
    if name not in archive.namelist():
        return []
    root = ET.fromstring(archive.read(name))
    return [text_of(item) for item in root.findall("m:si", NS)]


def read_sheet(archive: ZipFile, target: str, shared: list[str]) -> list[dict[int, str]]:
    root = ET.fromstring(archive.read(target))
    rows: list[dict[int, str]] = []
    for row_node in root.findall("m:sheetData/m:row", NS):
        row: dict[int, str] = {}
        for cell in row_node.findall("m:c", NS):
            cell_type = cell.attrib.get("t")
            value = text_of(cell.find("m:v", NS))
            if cell_type == "s" and value.isdigit() and int(value) < len(shared):
                value = shared[int(value)]
            elif cell_type == "inlineStr":
                value = text_of(cell.find("m:is", NS))
            row[column_index(cell.attrib.get("r", "A1"))] = value.strip()
        if row:
            rows.append(row)
    return rows


def canonical_type(raw: str, options: list[str]) -> str:
    text = normalize_header(raw)
    if "多选" in text:
        return "multiple"
    if "不定项" in text:
        return "indefinite"
    if "判断" in text or "是非" in text:
        return "true_false"
    if "填空" in text or "简答" in text:
        return "fill_blank"
    if "单选" in text:
        return "single"
    return "fill_blank" if not any(options) else "single"


def answer_fields(raw: str, question_type: str) -> tuple[str, list[int], str]:
    answer = raw.strip()
    letters = re.sub(r"[^A-Fa-f]", "", answer).upper()
    if letters and len(letters) == len(answer.replace(" ", "")) and re.fullmatch(r"[A-F]+", letters):
        unique = "".join(dict.fromkeys(letters))
        return unique, [ord(letter) - ord("A") for letter in unique], ""
    if question_type == "fill_blank" or not letters:
        return "", [], answer
    return letters, [ord(letter) - ord("A") for letter in dict.fromkeys(letters)], answer


def collect_sheet_records(workbook: Path) -> tuple[list[dict], list[dict]]:
    records: list[dict] = []
    quarantine: list[dict] = []
    with ZipFile(workbook) as archive:
        shared = load_shared_strings(archive)
        workbook_xml = ET.fromstring(archive.read("xl/workbook.xml"))
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        relation_map = {
            item.attrib["Id"]: resolve_target(item.attrib["Target"])
            for item in relationships.findall(f"{REL_NS}Relationship")
        }
        for sheet in workbook_xml.findall("m:sheets/m:sheet", NS):
            sheet_name = sheet.attrib.get("name", "")
            target = relation_map.get(sheet.attrib.get(RID, ""), "")
            if target not in archive.namelist():
                continue
            rows = read_sheet(archive, target, shared)
            header_position = next(
                (
                    position
                    for position, row in enumerate(rows)
                    if {header_role(value) for value in row.values()} >= {"question", "answer"}
                ),
                None,
            )
            if header_position is None:
                continue
            header = rows[header_position]
            roles = {header_role(value): column for column, value in header.items() if header_role(value)}
            option_columns = {role[-1]: column for role, column in roles.items() if role.startswith("option_")}
            for row_number, row in enumerate(rows[header_position + 1 :], start=header_position + 2):
                question = row.get(roles["question"], "").strip()
                answer_raw = row.get(roles["answer"], "").strip()
                origin = {"file": workbook.name, "sheet": sheet_name, "row": row_number}
                if not question or not answer_raw:
                    if any(value.strip() for value in row.values()):
                        quarantine.append({"origin": origin, "reason": "blank_question" if not question else "blank_answer"})
                    continue
                options = [
                    {"index": ord(letter) - ord("A"), "text": row.get(option_columns[letter], "").strip()}
                    for letter in "ABCDEF"
                    if letter in option_columns and row.get(option_columns[letter], "").strip()
                ]
                question_type = canonical_type(row.get(roles.get("type", -1), ""), [item["text"] for item in options])
                answer, answer_indices, answer_text = answer_fields(answer_raw, question_type)
                record_id = f"{workbook.name}:{sheet_name}:{row_number}"
                records.append(
                    {
                        "id": record_id,
                        "question": question,
                        "type": question_type,
                        "answer": answer,
                        "answer_indices": answer_indices,
                        "answer_text": answer_text,
                        "options": options,
                        "sources": [origin],
                    }
                )
    return records, quarantine


def grams(value: str) -> list[str]:
    if len(value) <= ANCHOR_LENGTH:
        return [value] if value else []
    return list(dict.fromkeys(value[index : index + ANCHOR_LENGTH] for index in range(len(value) - ANCHOR_LENGTH + 1)))


def attach_anchors(records: list[dict]) -> None:
    document_frequency: Counter[str] = Counter()
    for record in records:
        document_frequency.update(set(grams(record["normalized_question"])))
    for record in records:
        normalized = record["normalized_question"]
        candidates = grams(normalized)
        if len(candidates) <= MAX_ANCHORS:
            record["anchors"] = candidates
            continue
        last_position = max(0, len(normalized) - ANCHOR_LENGTH)
        positions = [0, round(last_position * 0.5), last_position]
        chosen: list[str] = []
        for target in positions:
            best = min(
                candidates,
                key=lambda gram: (
                    abs(normalized.index(gram) - target),
                    document_frequency[gram],
                ),
            )
            if best not in chosen:
                chosen.append(best)
        record["anchors"] = chosen


def build_artifact(root: Path) -> dict:
    records: list[dict] = []
    quarantine: list[dict] = []
    for workbook in sorted(root.glob("*.xlsx")):
        workbook_records, workbook_quarantine = collect_sheet_records(workbook)
        records.extend(workbook_records)
        quarantine.extend(workbook_quarantine)
    for record in records:
        record["normalized_question"] = normalize_question(record["question"])
    attach_anchors(records)

    groups: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        groups[record["normalized_question"]].append(record)
    questions: list[dict] = []
    conflicts: list[dict] = []
    for normalized, variants in groups.items():
        signatures = {
            json.dumps(
                (item["type"], item["answer"], item["answer_text"], item["options"]),
                ensure_ascii=False,
                sort_keys=True,
            )
            for item in variants
        }
        if len(signatures) > 1:
            conflicts.append(
                {
                    "normalized_question": normalized,
                    "question": variants[0]["question"],
                    "records": [item["id"] for item in variants],
                }
            )
            continue
        canonical = dict(variants[0])
        canonical["sources"] = [source for item in variants for source in item["sources"]]
        questions.append(canonical)

    type_counts = Counter(record["type"] for record in records)
    return {
        "schema_version": 1,
        "records": records,
        "questions": questions,
        "conflicts": conflicts,
        "quarantine": quarantine,
        "stats": {
            "workbooks": len(list(root.glob("*.xlsx"))),
            "records": len(records),
            "unique_questions": len(groups),
            "matchable_questions": len(questions),
            "conflicts": len(conflicts),
            "quarantine": len(quarantine),
            "types": dict(sorted(type_counts.items())),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, nargs="+")
    args = parser.parse_args()
    artifact = build_artifact(args.input)
    payload = json.dumps(artifact, ensure_ascii=False, indent=2) + "\n"
    for output in args.output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload, encoding="utf-8")
    print(json.dumps(artifact["stats"], ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
