#!/usr/bin/env -S uv run --script --project scripts
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import TypeAlias, TypeGuard, cast

try:
    import yaml
except ImportError as import_error:
    raise SystemExit(
        "PyYAML is required. Run `uv sync --project scripts` from the repository root."
    ) from import_error


YamlScalar: TypeAlias = str | int | float | bool | None
YamlValue: TypeAlias = YamlScalar | list["YamlValue"] | dict[str, "YamlValue"]
YamlMapping: TypeAlias = dict[str, YamlValue]
RenderableValue: TypeAlias = str | int | float | bool
StringMapping: TypeAlias = dict[str, str]
RenderContext: TypeAlias = dict[str, StringMapping]


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
MARKETING_ROOT = REPO_ROOT / ".marketing"
FEATURES_PATH = REPO_ROOT / "docs" / "features.yml"
CONFIG_PATH = MARKETING_ROOT / "config.yaml"
CHANGELOG_PATH = REPO_ROOT / "CHANGELOG.md"
TEMPLATES_DIR = MARKETING_ROOT / "templates"
GENERATED_DIR = MARKETING_ROOT / "generated"

PLACEHOLDER_PATTERN: re.Pattern[str] = re.compile(r"{{\s*([a-zA-Z0-9_.-]+)\s*}}")
RAW_PLACEHOLDER_PATTERN: re.Pattern[str] = re.compile(r"{{[^{}]*}}")
MARKDOWN_HEADING_PATTERN: re.Pattern[str] = re.compile(r"^## \[?([^]\s]+)]?")
METRIC_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\b\d[\d,.]*\+?\s+(downloads|installs|reviews|users|stars)\b", re.I),
    re.compile(r"\b(revenue|conversion)\b.{0,48}\b\d[\d,.%]*", re.I),
)
SUPPORT_FILE_NAMES: frozenset[str] = frozenset(
    {"README.md", "FACT_CHECK.md", "CHECKLIST.md", "POSTING_PLAN.md"}
)
SUPPORT_FILE_NAME_KEYS: frozenset[str] = frozenset(
    support_file_name.casefold() for support_file_name in SUPPORT_FILE_NAMES
)
FEATURE_TIERS: frozenset[str] = frozenset({"free", "paid"})
UNRESOLVED_PLACEHOLDER_PREFIX = "TODO_VERIFY: unresolved placeholder"


@dataclass(frozen=True, slots=True)
class FeatureRecord:
    feature_id: str
    title: str
    tier: str
    introduced: str


@dataclass(frozen=True, slots=True)
class RenderedFile:
    name: str
    content: str
    is_public_draft: bool


@dataclass(frozen=True, slots=True)
class GuardReport:
    hard_blocks: list[str]
    warnings: list[str]


@dataclass(frozen=True, slots=True)
class LatestChangelog:
    version: str
    summary: str


def is_object_list(value: object) -> TypeGuard[list[object]]:
    return isinstance(value, list)


def is_object_dict(value: object) -> TypeGuard[dict[object, object]]:
    return isinstance(value, dict)


def main() -> int:
    arguments = parse_args()
    campaign_name = slugify(arguments.name)
    config_data = load_yaml_mapping(CONFIG_PATH)
    features_data = load_yaml_mapping(FEATURES_PATH)
    campaign_mode = get_campaign_mode(config_data, arguments.mode)
    template_names = read_template_names(campaign_mode, arguments.mode)
    context = build_context(config_data, features_data, campaign_mode, arguments.mode, campaign_name)

    rendered_files = render_templates(template_names, context)
    guard_report = evaluate_guardrails(config_data, features_data, rendered_files)

    ensure_generated_output_root_is_safe()
    output_directory = GENERATED_DIR / f"{date.today().isoformat()}-{campaign_name}"
    if output_directory.exists():
        raise SystemExit(f"Campaign output already exists: {output_directory}")
    try:
        output_directory.mkdir(parents=True)
    except OSError as output_error:
        raise SystemExit(
            f"Failed to create campaign output directory {output_directory}: {output_error}"
        ) from output_error

    for rendered_file in rendered_files + build_support_files(
        template_names,
        context,
        guard_report,
    ):
        output_path = output_directory / rendered_file.name
        try:
            output_path.write_text(rendered_file.content, encoding="utf-8")
        except OSError as output_error:
            raise SystemExit(
                f"Failed to write campaign file {output_path}: {output_error}"
            ) from output_error

    print(f"Generated campaign: {output_directory}")
    print(f"Rendered drafts: {', '.join(template_names)}")
    print(f"Warnings: {len(guard_report.warnings)}")
    print(f"Hard blocks: {len(guard_report.hard_blocks)}")

    if guard_report.hard_blocks:
        print("Hard-blocked claims were found. Review FACT_CHECK.md.", file=sys.stderr)
        return 2

    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate private Ayu Islands marketing campaign drafts.",
    )
    parser.add_argument(
        "--mode",
        required=True,
        help="Campaign mode from .marketing/config.yaml.",
    )
    parser.add_argument(
        "--name",
        required=True,
        help="Human-readable campaign name. It will be slugified for the output path.",
    )
    return parser.parse_args()


def load_yaml_mapping(path: Path) -> YamlMapping:
    if not path.exists():
        raise SystemExit(f"Required YAML file is missing: {path}")

    try:
        raw_content = path.read_text(encoding="utf-8")
        parsed_yaml = cast(object | None, yaml.compose(raw_content))  # pyright: ignore[reportUnknownMemberType]
        if parsed_yaml is not None:
            reject_duplicate_yaml_keys(parsed_yaml, path.name)
        loaded_yaml: object = yaml.safe_load(raw_content)
        raw_yaml: object = loaded_yaml if loaded_yaml is not None else {}
        normalized_yaml = normalize_yaml(raw_yaml, path.name)
    except (OSError, UnicodeError) as file_error:
        raise SystemExit(f"Failed to read YAML file {path}: {file_error}") from file_error
    except yaml.YAMLError as yaml_error:
        raise SystemExit(f"Failed to parse YAML file {path}: {yaml_error}") from yaml_error
    except RecursionError as recursion_error:
        raise SystemExit(
            f"Failed to parse YAML file {path}: recursive aliases are not supported."
        ) from recursion_error

    if not isinstance(normalized_yaml, dict):
        raise SystemExit(f"Expected YAML mapping at top level in {path}")

    return normalized_yaml


def ensure_generated_output_root_is_safe() -> None:
    for output_root in (MARKETING_ROOT, GENERATED_DIR):
        if output_root.is_symlink():
            raise SystemExit(f"Campaign output root must not be a symlink: {output_root}")


def reject_duplicate_yaml_keys(
    node: object,
    source_name: str,
    active_nodes: set[int] | None = None,
) -> None:
    node_stack = set() if active_nodes is None else active_nodes

    if isinstance(node, yaml.nodes.MappingNode):
        ensure_yaml_node_is_not_recursive(node, source_name, node_stack)
        try:
            seen_keys: set[str] = set()
            for key_node, value_node in node.value:
                if isinstance(key_node, yaml.nodes.ScalarNode):
                    key_name = key_node.value
                    if key_name in seen_keys:
                        line_number = key_node.start_mark.line + 1
                        raise SystemExit(
                            f"Duplicate YAML key `{key_name}` in {source_name}:{line_number}."
                        )
                    seen_keys.add(key_name)
                reject_duplicate_yaml_keys(value_node, source_name, node_stack)
            return
        finally:
            node_stack.remove(id(node))

    if isinstance(node, yaml.nodes.SequenceNode):
        ensure_yaml_node_is_not_recursive(node, source_name, node_stack)
        try:
            for value_node in node.value:
                reject_duplicate_yaml_keys(value_node, source_name, node_stack)
        finally:
            node_stack.remove(id(node))


def ensure_yaml_node_is_not_recursive(
    node: yaml.nodes.CollectionNode,
    source_name: str,
    active_nodes: set[int],
) -> None:
    node_id = id(node)
    if node_id in active_nodes:
        raise SystemExit(f"Recursive YAML aliases are not supported in {source_name}.")
    active_nodes.add(node_id)


def normalize_yaml(
    value: object,
    source_name: str,
    active_values: set[int] | None = None,
) -> YamlValue:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value

    value_stack = set() if active_values is None else active_values
    if is_object_list(value):
        ensure_yaml_value_is_not_recursive(value, source_name, value_stack)
        try:
            return [normalize_yaml(item, source_name, value_stack) for item in value]
        finally:
            value_stack.remove(id(value))

    if is_object_dict(value):
        ensure_yaml_value_is_not_recursive(value, source_name, value_stack)
        try:
            normalized_mapping: YamlMapping = {}
            for key, item_value in value.items():
                if not isinstance(key, str):
                    raise SystemExit(f"Expected string YAML keys in {source_name}: {key!r}")
                normalized_mapping[key] = normalize_yaml(item_value, source_name, value_stack)
            return normalized_mapping
        finally:
            value_stack.remove(id(value))

    raise SystemExit(f"Unsupported YAML value in {source_name}: {type(value).__name__}")


def ensure_yaml_value_is_not_recursive(
    value: list[object] | dict[object, object],
    source_name: str,
    active_values: set[int],
) -> None:
    value_id = id(value)
    if value_id in active_values:
        raise SystemExit(f"Recursive YAML aliases are not supported in {source_name}.")
    active_values.add(value_id)


def get_campaign_mode(config_data: YamlMapping, mode: str) -> YamlMapping:
    campaign_modes = require_mapping(config_data, "campaign_modes")
    selected_mode = campaign_modes.get(mode)
    if isinstance(selected_mode, dict):
        return selected_mode

    available_modes = ", ".join(sorted(campaign_modes))
    raise SystemExit(f"Unknown campaign mode `{mode}`. Available modes: {available_modes}")


def read_template_names(campaign_mode: YamlMapping, mode: str) -> list[str]:
    raw_templates = campaign_mode.get("templates")
    if not isinstance(raw_templates, list):
        raise SystemExit(f"Campaign mode `{mode}` must define a `templates` list.")

    template_names: list[str] = []
    template_name_keys: set[str] = set()
    for raw_template_name in raw_templates:
        if not isinstance(raw_template_name, str):
            raise SystemExit(f"Campaign mode `{mode}` contains a non-string template name.")
        template_name = validate_template_name(raw_template_name)
        template_name_key = template_name.casefold()
        if template_name_key in template_name_keys:
            raise SystemExit(f"Template `{template_name}` is selected more than once.")
        if template_name_key in SUPPORT_FILE_NAME_KEYS:
            raise SystemExit(f"Template `{template_name}` conflicts with a generated support file.")
        template_name_keys.add(template_name_key)
        template_names.append(template_name)

    if not template_names:
        raise SystemExit(f"Campaign mode `{mode}` does not select any templates.")

    return template_names


def validate_template_name(template_name: str) -> str:
    template_path = Path(template_name)
    if (
        template_path.is_absolute()
        or template_name != template_path.name
        or "\\" in template_name
    ):
        raise SystemExit(f"Template `{template_name}` must be a file name in .marketing/templates.")
    if template_path.suffix != ".md":
        raise SystemExit(f"Template `{template_name}` must be a Markdown file.")
    if not template_path.stem:
        raise SystemExit(f"Template `{template_name}` must include a file name.")

    return template_name


def build_context(
    config_data: YamlMapping,
    features_data: YamlMapping,
    campaign_mode: YamlMapping,
    mode: str,
    campaign_name: str,
) -> RenderContext:
    product = require_string_mapping(config_data, "product")
    pricing = require_string_mapping(config_data, "pricing")
    launch_status = require_string_mapping(config_data, "launch_status")
    feature_summary = summarize_features(features_data)
    manual_verification = read_string_list(config_data, "manual_verification")
    social_proof = format_social_proof(read_mapping_list(config_data, "social_proof"))
    latest_changelog = read_latest_changelog()
    pricing_summary = format_pricing_summary(pricing)
    launch_status_summary = format_status_summary(launch_status)

    verification_todos = (
        "\n".join(
            f"- TODO_VERIFY: {verification_item}"
            for verification_item in manual_verification
        )
        or "- TODO_VERIFY: no manual verification checklist configured"
    )

    campaign = {
        "mode": mode,
        "name": campaign_name,
        "generated_date": date.today().isoformat(),
        "description": read_optional_string(campaign_mode, "description", ""),
    }
    facts = {
        **feature_summary,
        "latest_version": latest_changelog.version,
        "latest_changelog_summary": latest_changelog.summary,
        "pricing_summary": pricing_summary,
        "launch_status_summary": launch_status_summary,
        "social_proof_summary": social_proof,
    }
    pricing_context = {
        **pricing,
        "summary": pricing_summary,
    }
    launch_status_context = {
        **launch_status,
        "summary": launch_status_summary,
    }
    social_proof_context = {
        "summary": social_proof,
    }
    verification = {
        "todos": verification_todos,
    }

    return {
        "product": product,
        "campaign": campaign,
        "facts": facts,
        "pricing": pricing_context,
        "launch_status": launch_status_context,
        "social_proof": social_proof_context,
        "verification": verification,
    }


def require_mapping(source: YamlMapping, key: str) -> YamlMapping:
    value = source.get(key)
    if isinstance(value, dict):
        return value
    raise SystemExit(f"Expected `{key}` to be a YAML mapping.")


def require_string_mapping(source: YamlMapping, key: str) -> StringMapping:
    value = require_mapping(source, key)
    return {
        item_key: read_mapping_scalar(item_value, f"{key}.{item_key}")
        for item_key, item_value in value.items()
    }


def read_mapping_scalar(value: YamlValue, location: str) -> str:
    string_value = scalar_to_string(value, location)
    if location.startswith("pricing.") or string_value.strip():
        return string_value
    raise SystemExit(f"Expected `{location}` to be a non-empty scalar.")


def read_string_list(source: YamlMapping, key: str) -> list[str]:
    value = source.get(key)
    if value is None:
        return []
    if not isinstance(value, list):
        raise SystemExit(f"Expected `{key}` to be a YAML list.")
    return parse_string_list(value, key)


def read_required_string_list(source: YamlMapping, key: str) -> list[str]:
    return parse_string_list(require_yaml_list(source, key), key)


def parse_string_list(value: list[YamlValue], key: str) -> list[str]:
    strings: list[str] = []
    for index, item in enumerate(value):
        if string_value := scalar_to_string(item, f"{key}[{index}]").strip():
            strings.append(string_value)
        else:
            raise SystemExit(f"Expected `{key}[{index}]` to be a non-empty scalar.")
    return strings


def read_mapping_list(source: YamlMapping, key: str) -> list[YamlMapping]:
    value = source.get(key)
    if value is None:
        return []
    if not isinstance(value, list):
        raise SystemExit(f"Expected `{key}` to be a YAML list.")
    return parse_mapping_list(value, key)


def read_required_mapping_list(source: YamlMapping, key: str) -> list[YamlMapping]:
    return parse_mapping_list(require_yaml_list(source, key), key)


def require_yaml_list(source: YamlMapping, key: str) -> list[YamlValue]:
    if key not in source:
        raise SystemExit(f"Expected `{key}` to be a YAML list.")

    value = source[key]
    if isinstance(value, list):
        return value
    raise SystemExit(f"Expected `{key}` to be a YAML list.")


def parse_mapping_list(value: list[YamlValue], key: str) -> list[YamlMapping]:
    mappings: list[YamlMapping] = []
    for index, item in enumerate(value):
        if not isinstance(item, dict):
            raise SystemExit(f"Expected `{key}[{index}]` to be a YAML mapping.")
        mappings.append(item)
    return mappings


def read_optional_string(source: YamlMapping, key: str, default: str) -> str:
    value = source.get(key)
    return default if value is None else scalar_to_string(value, key)


def read_required_string(source: YamlMapping, key: str, location: str) -> str:
    if key not in source:
        raise SystemExit(f"Expected `{location}.{key}` to be a non-empty scalar.")

    if value := scalar_to_string(source[key], f"{location}.{key}").strip():
        return value
    raise SystemExit(f"Expected `{location}.{key}` to be a non-empty scalar.")


def read_optional_non_empty_string(
    source: YamlMapping,
    key: str,
    default: str,
    location: str,
) -> str:
    if key not in source:
        return default
    return read_required_string(source, key, location)


def scalar_to_string(value: YamlValue, location: str) -> str:
    if value is None:
        return ""
    if isinstance(value, (str, int, float, bool)):
        return str(value)
    raise SystemExit(f"Expected scalar value at `{location}`.")


def read_conditional_claims(guardrails: YamlMapping) -> list[YamlMapping]:
    conditional_claims = read_required_mapping_list(guardrails, "conditional_claims")
    for index, conditional_claim in enumerate(conditional_claims):
        location = f"conditional_claims[{index}]"
        read_required_string(conditional_claim, "phrase", location)
        if "message" in conditional_claim:
            read_required_string(conditional_claim, "message", location)
        if "requires_feature_id" in conditional_claim:
            read_required_string(conditional_claim, "requires_feature_id", location)
        if "requires_feature_id" in conditional_claim and "requires" in conditional_claim:
            raise SystemExit(
                f"Expected `{location}` to define only one requirement type."
            )
        requires = conditional_claim.get("requires")
        if requires is not None and not isinstance(requires, dict):
            raise SystemExit(f"Expected `{location}.requires` to be a YAML mapping.")
        if requires is not None:
            read_required_string(requires, "path", f"{location}.requires")
            read_required_string(requires, "value", f"{location}.requires")
    return conditional_claims


def summarize_features(features_data: YamlMapping) -> StringMapping:
    feature_records = flatten_features(features_data)
    free_features = [
        feature_record for feature_record in feature_records if feature_record.tier == "free"
    ]
    paid_features = [
        feature_record for feature_record in feature_records if feature_record.tier == "paid"
    ]

    return {
        "free_feature_summary": format_feature_bullets(free_features),
        "paid_feature_summary": format_feature_bullets(paid_features),
        "free_feature_summary_inline": format_inline_features(free_features),
        "paid_feature_summary_inline": format_inline_features(paid_features),
        "released_feature_ids": ", ".join(feature_record.feature_id for feature_record in feature_records),
    }


def flatten_features(features_data: YamlMapping) -> list[FeatureRecord]:
    categories = require_mapping(features_data, "categories")
    feature_records: list[FeatureRecord] = []

    for category_name, category_data in categories.items():
        if not isinstance(category_data, dict):
            raise SystemExit(f"Expected category `{category_name}` to be a YAML mapping.")

        raw_features = category_data.get("features")
        if raw_features is None:
            continue
        if not isinstance(raw_features, list):
            raise SystemExit(f"Expected category `{category_name}` features to be a YAML list.")

        for index, raw_feature in enumerate(raw_features):
            if not isinstance(raw_feature, dict):
                raise SystemExit(
                    f"Expected feature `{category_name}.features[{index}]` to be a YAML mapping."
                )
            feature_records.append(
                normalize_feature_entry(raw_feature, f"{category_name}.features[{index}]")
            )

    return feature_records


def normalize_feature_entry(feature_data: YamlMapping, location: str) -> FeatureRecord:
    feature_id = read_required_string(feature_data, "id", location)
    title = read_required_string(feature_data, "title", location)
    tier = read_required_string(feature_data, "tier", location).lower()
    introduced = read_required_string(feature_data, "introduced", location)

    if tier not in FEATURE_TIERS:
        allowed_tiers = ", ".join(sorted(FEATURE_TIERS))
        raise SystemExit(f"Expected `{location}.tier` to be one of: {allowed_tiers}.")

    return FeatureRecord(
        feature_id=feature_id,
        title=title,
        tier=tier,
        introduced=introduced,
    )


def format_feature_bullets(feature_records: list[FeatureRecord]) -> str:
    if not feature_records:
        return "- TODO_VERIFY: no released features found"

    return "\n".join(
        f"- {feature_record.title} ({feature_record.tier}, introduced {feature_record.introduced})"
        for feature_record in feature_records
    )


def format_inline_features(feature_records: list[FeatureRecord]) -> str:
    if not feature_records:
        return "TODO_VERIFY: no released features found"
    return ", ".join(feature_record.title for feature_record in feature_records[:5])


def read_latest_changelog() -> LatestChangelog:
    if not CHANGELOG_PATH.exists():
        missing_changelog = "TODO_VERIFY: CHANGELOG.md not found"
        return LatestChangelog(missing_changelog, f"- {missing_changelog}")

    latest_version: str | None = None
    summary_lines: list[str] = []

    try:
        changelog_content = CHANGELOG_PATH.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as changelog_error:
        failed_changelog = f"TODO_VERIFY: failed to read CHANGELOG.md: {changelog_error}"
        return LatestChangelog(failed_changelog, f"- {failed_changelog}")

    for line in changelog_content.splitlines():
        if match := MARKDOWN_HEADING_PATTERN.match(line):
            if latest_version is not None:
                break
            latest_version = match.group(1)
            continue
        if latest_version is not None and line.strip():
            summary_lines.append(line)

    return LatestChangelog(
        latest_version or "TODO_VERIFY: no changelog version heading found",
        "\n".join(summary_lines) if summary_lines else "- TODO_VERIFY: no latest changelog body found",
    )


def format_pricing_summary(pricing: StringMapping) -> str:
    public_claim_status = pricing.get("public_claim_status", "not_ready")
    if public_claim_status != "ready":
        return "TODO_VERIFY: pricing copy is not ready for public use"

    individual = pricing_summary_value(pricing, "individual")
    commercial = pricing_summary_value(pricing, "commercial")
    model = pricing_summary_value(pricing, "model")
    return f"{individual} individual / {commercial} commercial, {model}"


def pricing_summary_value(pricing: StringMapping, key: str) -> str:
    if value := pricing.get(key, "").strip():
        return value
    return f"TODO_VERIFY {key}"


def format_status_summary(statuses: StringMapping) -> str:
    if not statuses:
        return "- TODO_VERIFY: no launch status configured"

    return "\n".join(f"- {key}: {value}" for key, value in statuses.items())


def format_social_proof(social_proof_entries: list[YamlMapping]) -> str:
    verified_entries: list[str] = []
    for index, entry in enumerate(social_proof_entries):
        location = f"social_proof[{index}]"
        source = read_required_string(entry, "source", location)
        label = read_optional_string(entry, "label", "social proof")
        quote = read_required_string(entry, "quote", location)
        status = read_optional_non_empty_string(entry, "status", "TODO_VERIFY", location)
        verified_entries.append(f'- {label}: "{quote}" (source: {source}, status: {status})')

    if not verified_entries:
        return "- TODO_VERIFY: no sourced social proof configured"

    return "\n".join(verified_entries)


def render_templates(template_names: list[str], context: RenderContext) -> list[RenderedFile]:
    rendered_files: list[RenderedFile] = []

    for template_name in template_names:
        template_path = TEMPLATES_DIR / template_name
        if not template_path.exists():
            raise SystemExit(f"Selected template is missing: {template_path}")

        try:
            template_content = template_path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as template_error:
            raise SystemExit(
                f"Failed to read selected template {template_path}: {template_error}"
            ) from template_error
        rendered_files.append(
            RenderedFile(
                name=template_name,
                content=render_text(template_content, context),
                is_public_draft=True,
            )
        )

    return rendered_files


def render_text(template_content: str, context: RenderContext) -> str:
    def replace_placeholder(match: re.Match[str]) -> str:
        placeholder = match.group(1)
        value = lookup_render_value(context, placeholder)
        if value is None:
            return f"TODO_VERIFY: unresolved placeholder {placeholder}"
        return str(value)

    return PLACEHOLDER_PATTERN.sub(replace_placeholder, template_content)


def lookup_render_value(context: RenderContext, path: str) -> RenderableValue | None:
    section_name, separator, value_name = path.partition(".")
    if not separator or not section_name or not value_name or "." in value_name:
        return None

    section = context.get(section_name)
    if section is None:
        return None

    value = section.get(value_name)
    return None if isinstance(value, str) and not value.strip() else value


def evaluate_guardrails(
    config_data: YamlMapping,
    features_data: YamlMapping,
    rendered_files: list[RenderedFile],
) -> GuardReport:
    guardrails = require_mapping(config_data, "guardrails")
    blocked_phrases = read_required_string_list(guardrails, "blocked_phrases")
    risky_phrases = read_required_string_list(guardrails, "risky_phrases")
    conditional_claims = read_conditional_claims(guardrails)
    feature_records = flatten_features(features_data)
    hard_blocks: list[str] = []
    warnings: list[str] = []

    for rendered_file in rendered_files:
        if not rendered_file.is_public_draft:
            continue

        lower_content = rendered_file.content.lower()
        hard_blocks.extend(
            f"{rendered_file.name}: blocked phrase `{blocked_phrase}`"
            for blocked_phrase in blocked_phrases
            if blocked_phrase.lower() in lower_content
        )
        warnings.extend(
            f"{rendered_file.name}: TODO_VERIFY: risky phrase `{risky_phrase}` needs review"
            for risky_phrase in risky_phrases
            if risky_phrase.lower() in lower_content
        )
        for conditional_claim in conditional_claims:
            if warning := evaluate_conditional_claim(
                config_data,
                feature_records,
                conditional_claim,
                lower_content,
            ):
                warnings.append(f"{rendered_file.name}: {warning}")

        warnings.extend(
            f"{rendered_file.name}: {unresolved_marker}"
            for unresolved_marker in find_unresolved_markers(
                rendered_file.content
            )
            if UNRESOLVED_PLACEHOLDER_PREFIX not in unresolved_marker
        )
        hard_blocks.extend(
            f"{rendered_file.name}: {unresolved_marker}"
            for unresolved_marker in find_unresolved_markers(
                rendered_file.content
            )
            if UNRESOLVED_PLACEHOLDER_PREFIX in unresolved_marker
        )
        hard_blocks.extend(
            f"{rendered_file.name}: raw template placeholder `{raw_placeholder}` was not rendered"
            for raw_placeholder in find_raw_placeholders(rendered_file.content)
        )
        warnings.extend(
            f"{rendered_file.name}: TODO_VERIFY: metric claim `{metric_claim}` needs a source"
            for metric_claim in find_metric_claims(rendered_file.content)
        )
    return GuardReport(hard_blocks=hard_blocks, warnings=warnings)


def evaluate_conditional_claim(
    config_data: YamlMapping,
    feature_records: list[FeatureRecord],
    conditional_claim: YamlMapping,
    lower_content: str,
) -> str | None:
    phrase = read_required_string(conditional_claim, "phrase", "conditional_claim")
    if phrase.lower() not in lower_content:
        return None

    message = read_optional_non_empty_string(
        conditional_claim,
        "message",
        f"TODO_VERIFY: conditional claim `{phrase}` requires manual verification.",
        "conditional_claim",
    )
    if required_feature_id := read_optional_string(
        conditional_claim, "requires_feature_id", ""
    ):
        if any(feature_record.feature_id == required_feature_id for feature_record in feature_records):
            return None
        return message

    requires = conditional_claim.get("requires")
    if isinstance(requires, dict):
        if "path" not in requires or "value" not in requires:
            return message
        path = read_optional_string(requires, "path", "")
        if not path:
            return message
        expected_value = read_optional_string(requires, "value", "")
        actual_value = lookup_yaml_value(config_data, path)
        if yaml_value_matches(actual_value, expected_value):
            return None

    return message


def lookup_yaml_value(source: YamlMapping, path: str) -> YamlValue | None:
    if not path:
        return None

    current_value: YamlValue = source
    for segment in path.split("."):
        if not isinstance(current_value, dict):
            return None
        next_value = current_value.get(segment)
        if next_value is None:
            return None
        current_value = next_value

    return current_value


def yaml_value_matches(actual_value: YamlValue | None, expected_value: str) -> bool:
    if actual_value is None:
        return False
    if isinstance(actual_value, (str, int, float, bool)):
        return str(actual_value) == expected_value
    return False


def find_unresolved_markers(content: str) -> list[str]:
    return [line.strip() for line in content.splitlines() if "TODO_VERIFY" in line]


def find_raw_placeholders(content: str) -> list[str]:
    raw_placeholders = {
        raw_placeholder.strip()
        for raw_placeholder in RAW_PLACEHOLDER_PATTERN.findall(content)
    }
    for line in content.splitlines():
        open_index = line.find("{{")
        while open_index != -1:
            close_index = line.find("}}", open_index + 2)
            if close_index == -1:
                raw_placeholders.add(line[open_index:].strip())
                break
            open_index = line.find("{{", close_index + 2)
    return sorted(raw_placeholders)


def find_metric_claims(content: str) -> list[str]:
    metric_claims: set[str] = set()
    for metric_pattern in METRIC_PATTERNS:
        for match in metric_pattern.finditer(content):
            metric_claims.add(match.group(0).strip())
    return sorted(metric_claims)


def build_support_files(
    template_names: list[str],
    context: RenderContext,
    guard_report: GuardReport,
) -> list[RenderedFile]:
    campaign_name = context["campaign"]["name"]
    campaign_mode = context["campaign"]["mode"]
    verification_todos = context["verification"]["todos"]
    draft_file_list = "\n".join(f"- `{template_name}`" for template_name in template_names)

    readme_content = "\n".join(
        [
            f"# {campaign_name}",
            "",
            f"Mode: `{campaign_mode}`",
            "",
            "Draft files:",
            "",
            draft_file_list,
            "",
            "Fact-check summary:",
            "",
            f"- Warnings: {len(guard_report.warnings)}",
            f"- Hard blocks: {len(guard_report.hard_blocks)}",
            "",
            "Review `FACT_CHECK.md` before publishing anything.",
            "",
        ]
    )
    fact_check_content = "\n".join(
        [
            "# Fact Check",
            "",
            "## Hard Blocks",
            "",
            format_report_lines(guard_report.hard_blocks, "No hard blocks."),
            "",
            "## Warnings",
            "",
            format_report_lines(guard_report.warnings, "No warnings."),
            "",
            "## Manual Verification",
            "",
            verification_todos,
            "",
        ]
    )
    checklist_content = "\n".join(
        [
            "# Campaign Checklist",
            "",
            "## Before Publishing",
            "",
            verification_todos,
            "",
            "## Safety",
            "",
            "- Confirm all generated files are reviewable Markdown.",
            "- Confirm no API posting integration is involved.",
            "- Confirm no secrets or `.env` files were read.",
            "- Confirm public claims match released features in `docs/features.yml`.",
            "",
        ]
    )
    posting_plan_content = "\n".join(
        [
            "# Posting Plan",
            "",
            f"Campaign mode: `{campaign_mode}`",
            "",
            "Draft files:",
            "",
            draft_file_list,
            "",
            "Manual channel routing:",
            "",
            "- Copy only after `FACT_CHECK.md` is clean.",
            "- Paste manually into the target channel.",
            "- Re-check final preview in the target platform UI.",
            "",
        ]
    )

    return [
        RenderedFile("README.md", readme_content, False),
        RenderedFile("FACT_CHECK.md", fact_check_content, False),
        RenderedFile("CHECKLIST.md", checklist_content, False),
        RenderedFile("POSTING_PLAN.md", posting_plan_content, False),
    ]


def format_report_lines(lines: list[str], empty_message: str) -> str:
    if not lines:
        return f"- {empty_message}"
    return "\n".join(f"- {line}" for line in lines)


def slugify(value: str) -> str:
    if slug := re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-"):
        return slug
    else:
        raise SystemExit("Campaign name must contain at least one ASCII letter or number.")


if __name__ == "__main__":
    raise SystemExit(main())
