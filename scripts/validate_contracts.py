#!/usr/bin/env python3
"""Validate the local API specifications and their HTTP examples without services."""

import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote, urldefrag, urljoin, urlparse

import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from openapi_spec_validator import validate
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012


ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "contracts"
METHODS = {"get", "post", "patch", "put", "delete"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    paths = [CONTRACTS / "schemas.yaml", *sorted((CONTRACTS / "openapi").glob("*.yaml"))]
    documents = {path.as_uri(): yaml.safe_load(path.read_text()) for path in paths}
    registry = Registry().with_resources(
        (uri, Resource.from_contents(doc, default_specification=DRAFT202012))
        for uri, doc in documents.items()
    )

    def resolve(reference, base):
        uri, fragment = urldefrag(urljoin(base, reference))
        require(uri in documents, f"Reference outside local contracts: {reference} from {base}")
        target = documents[uri]
        if fragment:
            require(fragment.startswith("/"), f"Unsupported reference anchor: {fragment}")
            for token in unquote(fragment)[1:].split("/"):
                key = token.replace("~1", "/").replace("~0", "~")
                target = target[int(key)] if isinstance(target, list) else target[key]
        return target

    def check_refs(value, base):
        if isinstance(value, dict):
            if "$ref" in value:
                resolve(value["$ref"], base)
            for child in value.values():
                check_refs(child, base)
        elif isinstance(value, list):
            for child in value:
                check_refs(child, base)

    def deref(value, base):
        while "$ref" in value:
            value = resolve(value["$ref"], base)
        return value

    def check_value(schema, value, base):
        # An inline schema must not replace its source document in the registry.
        def scoped_refs(node):
            if isinstance(node, dict):
                return {
                    key: urljoin(base, child) if key == "$ref" else scoped_refs(child)
                    for key, child in node.items()
                }
            if isinstance(node, list):
                return [scoped_refs(child) for child in node]
            return node

        scoped = scoped_refs(schema)
        Draft202012Validator(scoped, registry=registry, format_checker=FormatChecker()).validate(value)

    def check_example_consistency(value):
        if isinstance(value, list):
            for child in value:
                check_example_consistency(child)
        elif isinstance(value, dict):
            if {"score", "grade", "factors"} <= value.keys():
                require(sum(f["points"] for f in value["factors"]) == value["score"], "Risk factor sum mismatch")
                expected_grade = "LOW" if value["score"] >= 800 else "MEDIUM" if value["score"] >= 600 else "HIGH"
                require(value["grade"] == expected_grade, "Risk grade mismatch")
            if {"evaluatedTerms", "selectedTermsId", "offer"} <= value.keys():
                terms = {candidate["id"]: candidate for candidate in value["evaluatedTerms"]}
                selected = value["selectedTermsId"]
                if selected is not None:
                    require(selected in terms, "Selected terms were not evaluated")
                    require(value["risk"] == terms[selected]["risk"], "Selected risk mismatch")
                if value["offer"] is not None:
                    offer = value["offer"]
                    require(offer["termsId"] == selected, "Offer does not refer to selected terms")
                    for key in ("amountRub", "termMonths", "annualRateBps", "monthlyPaymentRub"):
                        require(offer[key] == terms[selected][key], f"Offer differs from evaluated {key}")
            if {"availableActions", "dataVersion", "customerId"} <= value.keys():
                for key in ("verification", "decision", "informationRequest", "manualReview", "acceptedOffer", "disbursement"):
                    result = value[key]
                    if result is not None:
                        require(result["dataVersion"] == value["dataVersion"], f"Application has obsolete {key}")
                        if "applicationId" in result:
                            require(result["applicationId"] == value["id"], f"Application has foreign {key}")
                if value["payoutCheck"] is not None:
                    require(value["payoutCheck"]["payoutVersion"] == value["payoutInstruction"]["payoutVersion"], "Payout version mismatch")
            if {"document", "signature", "signBy"} <= value.keys():
                document = value["document"]
                require(hashlib.sha256(document["content"].encode()).hexdigest() == document["sha256"], "Document SHA-256 mismatch")
                if value["signature"] is not None:
                    require(value["signature"]["documentSha256"] == document["sha256"], "Signature hash mismatch")
                    require(value["signature"]["documentVersion"] == document["version"], "Signature document version mismatch")
            for child in value.values():
                check_example_consistency(child)

    for uri, document in documents.items():
        check_refs(document, uri)
    Draft202012Validator.check_schema(documents[(CONTRACTS / "schemas.yaml").as_uri()])

    operations = {}
    for path in paths[1:]:
        uri = path.as_uri()
        document = documents[uri]
        validate(document, base_uri=uri)
        for route, item in document["paths"].items():
            for method, operation in item.items():
                if method not in METHODS:
                    continue
                opid = operation["operationId"]
                require(opid not in operations, f"Duplicate operationId: {opid}")
                operations[opid] = (uri, route, method, operation)
                parameters = [deref(p, uri) for p in operation.get("parameters", [])]
                route_names = set(re.findall(r"\{([^}]+)\}", route))
                declared_names = {p["name"] for p in parameters if p["in"] == "path"}
                require(route_names == declared_names, f"Path parameters mismatch: {opid}")

    examples = 0
    rejections = 0
    for file in sorted((CONTRACTS / "examples").glob("*.json")):
        document = json.loads(file.read_text())
        for rejected in document.get("rejections", []):
            try:
                check_value(
                    {"$ref": f"#/$defs/{rejected['schema']}"},
                    rejected["body"],
                    (CONTRACTS / "schemas.yaml").as_uri(),
                )
            except ValidationError:
                rejections += 1
            else:
                raise ValueError(f"Invalid sample unexpectedly accepted: {rejected['name']}")
        for index, exchange in enumerate(document.get("exchanges", []), 1):
            label = f"{file.name}:{index} {exchange['operationId']}"
            try:
                uri, _, _, operation = operations[exchange["operationId"]]
                request = exchange["request"]
                parameters = [deref(p, uri) for p in operation.get("parameters", [])]
                locations = {"path": "path", "query": "query", "header": "headers"}
                for parameter in parameters:
                    values = request.get(locations[parameter["in"]], {})
                    name = parameter["name"]
                    if name not in values:
                        require(not parameter.get("required"), f"Missing parameter {name}")
                    else:
                        check_value(parameter["schema"], values[name], uri)
                if "requestBody" in operation:
                    body = deref(operation["requestBody"], uri)
                    require("body" in request or not body.get("required"), "Missing request body")
                    if "body" in request:
                        check_value(body["content"]["application/json"]["schema"], request["body"], uri)
                else:
                    require("body" not in request, "Unexpected request body")
                response = exchange["response"]
                definition = deref(operation["responses"][str(response["status"])], uri)
                media = "application/problem+json" if response["status"] >= 400 else "application/json"
                check_value(definition["content"][media]["schema"], response["body"], uri)
                check_example_consistency(response["body"])
                for name, value in response.get("headers", {}).items():
                    check_value(deref(definition["headers"][name], uri)["schema"], value, uri)
                require("X-Request-Id" in response["headers"], "Response lacks X-Request-Id")
                if "ETag" in definition.get("headers", {}):
                    canonical = json.dumps(response["body"], sort_keys=True, ensure_ascii=False, separators=(",", ":"))
                    expected = '"' + hashlib.sha256(canonical.encode("utf-8")).hexdigest() + '"'
                    require(response["headers"].get("ETag") == expected, "ETag/representation mismatch")
                    require(response["headers"].get("Cache-Control") == "no-store", "Application response must not be cached")
                if media == "application/problem+json":
                    require(response["status"] == response["body"]["status"], "Problem status mismatch")
                examples += 1
            except Exception as error:
                raise ValueError(f"{label}: {error}") from error

    seed = json.loads((CONTRACTS / "examples/seed.json").read_text())
    base = (CONTRACTS / "schemas.yaml").as_uri()
    for name, values in [("Company", seed["companies"]), ("Product", seed["products"]), ("FinancialData", [seed["financialData"]])]:
        for value in values:
            check_value({"$ref": f"#/$defs/{name}"}, value, base)
    require(set(seed["customerCompanyIds"]) == {c["id"] for c in seed["companies"]}, "Seed company ownership mismatch")
    for account in seed["accountFixtures"]:
        check_value({"$ref": "#/$defs/Account"}, account, base)

    model = (ROOT / "docs/application-state-model.md").read_text()
    diagram_states = set(re.findall(r"<br/>([A-Z][A-Z_]+)", model))
    review_stages = {"ENRICHMENT", "SCORING"}
    require(review_stages <= diagram_states, "Business model lacks review substates")
    expected_statuses = (diagram_states - review_stages) | {"IN_REVIEW"}
    definitions = documents[base]["$defs"]
    require(set(definitions["ApplicationStatus"]["enum"]) == expected_statuses, "Contract statuses disagree with business model")
    require(set(definitions["ReviewStage"]["enum"]) == review_stages, "Contract reviewStage disagrees with business model")
    for uri, document in documents.items():
        if "openapi" in document:
            require(document["info"]["version"] == "2.0.0", f"Unexpected contract version: {uri}")
            require(all(route.startswith(("/v2/", "/health/")) for route in document["paths"]), f"Obsolete API path: {uri}")

    # Relative Markdown links are part of the handoff: all must resolve locally.
    for path in [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md")), CONTRACTS / "README.md", ROOT / "database/README.md"]:
        for target in re.findall(r"\]\(([^)]+)\)", path.read_text()):
            if urlparse(target).scheme or target.startswith("#"):
                continue
            require((path.parent / target.split("#", 1)[0]).exists(), f"Broken documentation link: {path.name}: {target}")

    print(f"OK: {len(paths) - 1} OpenAPI v2 specifications, common JSON Schema, {len(operations)} operations, {examples} HTTP examples, {rejections} rejected invalid samples, seed, business-state alignment and documentation links.")


if __name__ == "__main__":
    main()
