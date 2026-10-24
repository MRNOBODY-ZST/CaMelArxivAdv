from __future__ import annotations

import json
import re

import httpx
import pytest
from pydantic import SecretStr, ValidationError

from app.personalization.anthropic_client import AnthropicEmailClient
from app.personalization.contracts import PersonalizationCommand, PersonalizationTarget
from app.personalization.openai_client import OpenAIEmailClient, TransientGenerationError
from app.personalization.prompt import public_generation_input
from app.personalization.research_invitation import (
    CAMPAIGN_PURPOSE,
    CONNECTION_SLOT,
    COPY_INSTRUCTIONS,
    IDEA_SLOT,
    TEMPLATE_MARKER,
    ResearchInvitationCopy,
    parse_generation,
    render_invitation,
    reusable_template,
)
from personalization.helpers import command


def invitation_command() -> PersonalizationCommand:
    value = command(1).model_dump(by_alias=True)
    content = reusable_template("research@example.org")["content"]
    assert isinstance(content, dict)
    value["payload"].update(
        {
            "purpose": CAMPAIGN_PURPOSE,
            "templateSubject": content["subjectTemplate"],
            "templateHtml": content["htmlContent"],
            "templateText": content["textContent"],
        }
    )
    return PersonalizationCommand.model_validate(value)


def copy_payload() -> dict[str, str]:
    return {
        "subject": "A research collaboration on agent tool selection",
        "researchConnection": (
            "Your abstract describes evaluating tool selection by AI agents. "
            "That makes the evaluation setup a useful starting point for a joint study."
        ),
        "collaborationIdea": (
            "We could discuss a small comparison of agent task completion and token use "
            "under two tool-selection strategies, using a mutually agreed evaluation."
        ),
        "rationale": "The proposal follows the tool-selection evaluation in the supplied abstract.",
    }


@pytest.mark.parametrize("source", ["reusable", "default", "command"])
def test_future_research_copy_owns_qualified_trial_offer_and_manual_cta(source: str) -> None:
    active = invitation_command()
    if source == "reusable":
        content = reusable_template("research@example.org")["content"]
        assert isinstance(content, dict)
        assert content["replyTo"] == "research@example.org"
        bodies = (str(content["htmlContent"]), str(content["textContent"]))
    else:
        output = render_invitation(
            ResearchInvitationCopy.model_validate(copy_payload()),
            active.payload.targets[0],
            active if source == "command" else None,
        )
        bodies = (output.html, output.text)
    for body in bodies:
        assert (
            "Eligible new users can register and try Camel Hub with free trial credits, "
            "subject to current eligibility, availability, and daily-grant terms."
        ) in body
        assert "Activate and try Camel Hub" in body
        assert (
            "Trial credits are for eligible new users, subject to current eligibility, "
            "availability, and daily-grant terms."
        ) in body
        assert "subject to separate agreement on scope, resources, and terms" in body
        assert body.index("free trial credits") < body.index("model API token credits")
        assert body.count("{{partner_claim_url}}") == 1
        assert body.count("{{unsubscribe_url}}") == 1
        assert not re.search(r"(?:[$€£]\s*\d|\b\d[\d,.]*\s+(?:[\w-]+\s+){0,4}credits?\b)", body)
        for unsupported in (
            "guaranteed award", "no payment needed", "already funded", "unlimited access"
        ):
            assert unsupported not in body.lower()
    assert TEMPLATE_MARKER == "Camel Hub research invitation v1"
    assert bodies[0].count(f'title="{TEMPLATE_MARKER}"') == 1
    assert bodies[0].count(f'title="{CONNECTION_SLOT}"') == 1
    assert bodies[0].count(f'title="{IDEA_SLOT}"') == 1


def test_generation_brief_keeps_trial_terms_renderer_owned_and_support_secondary() -> None:
    assert "free trial credits for eligible new users" in CAMPAIGN_PURPOSE
    assert "current eligibility, availability, and daily-grant terms" in CAMPAIGN_PURPOSE
    assert "separate agreement" in CAMPAIGN_PURPOSE
    assert "trial offer and eligibility terms" in COPY_INSTRUCTIONS
    assert "Do not write trial terms or resource offers" in COPY_INSTRUCTIONS
    assert "free trial credits for eligible new users" in str(
        reusable_template("research@example.org")["description"]
    )


@pytest.mark.parametrize("claim_link", ["{{partner_claim_url}}", "https://api.camel-hub.com/"])
def test_supplied_frozen_command_html_never_gains_future_offer_or_rewrites_fixed_copy(
    claim_link: str,
) -> None:
    active = invitation_command()
    frozen = (
        '<table style="color:#102030"><tr><td>'
        '<p>Hello {{author_name}},</p><p>Operator-approved older invitation.</p>'
        '<span title="Camel Hub research connection">Older connection</span>'
        '<a href="{{paper_url}}">{{paper_title}}</a>'
        '<span title="Camel Hub collaboration idea">Older idea</span>'
        '<p>We can discuss compute sponsorship for an agreed research plan.</p>'
        f'<a href="{claim_link}" title="Camel Hub research invitation v1">Original CTA</a>'
        '<a href="https://api.camel-hub.com/">Original official link</a>'
        '<a href="{{unsubscribe_url}}">Original unsubscribe</a>'
        '</td></tr></table>'
    )
    active.payload.template_html = frozen
    frozen_text = f"Older fixed text: {claim_link} {{{{unsubscribe_url}}}}"
    active.payload.template_text = frozen_text
    target = active.payload.targets[0]
    expected = (
        frozen.replace("{{author_name}}", target.author_name or "research team")
        .replace("{{paper_url}}", target.paper_url)
        .replace("{{paper_title}}", target.paper_title)
        .replace("Older connection", copy_payload()["researchConnection"])
        .replace("Older idea", copy_payload()["collaborationIdea"])
    )
    output = render_invitation(
        ResearchInvitationCopy.model_validate(copy_payload()), target, active
    )
    assert output.html == expected
    assert parse_generation(copy_payload(), active, target) == output
    assert active.payload.template_html == frozen
    assert active.payload.template_text == frozen_text
    for body in (output.html, output.text):
        assert "Operator-approved older invitation." in body
        assert "We can discuss compute sponsorship for an agreed research plan." in body
        assert "Original CTA" in body
        assert claim_link in body
        assert "Original official link" in body
        assert "Original unsubscribe" in body
        assert "free trial" not in body.lower()
        assert "daily-grant" not in body
        assert "Activate and try Camel Hub" not in body


def test_new_research_template_offers_fixed_password_only_partner_activation() -> None:
    content = reusable_template("research@example.org")["content"]
    assert isinstance(content, dict)
    html = str(content["htmlContent"])
    text = str(content["textContent"])
    assert '<a href="{{partner_claim_url}}"' in html
    for body in (html, text):
        assert body.count("{{partner_claim_url}}") == 1
        assert "set a password" in body.lower()
        assert "log in" in body.lower()
        assert "bind" in body.lower()
        assert "$5" not in body and "$10" not in body


def test_new_research_template_preserves_activation_placeholder_in_both_generated_formats() -> None:
    active = invitation_command()
    output = parse_generation(copy_payload(), active, active.payload.targets[0])
    assert '<a href="{{partner_claim_url}}"' in output.html
    for body in (output.html, output.text):
        assert body.count("{{partner_claim_url}}") == 1
        assert body.count("{{unsubscribe_url}}") == 1
        assert "set a password" in body.lower()
        assert "log in" in body.lower()
        assert "$5" not in body and "$10" not in body


def test_legacy_research_template_does_not_gain_partner_activation() -> None:
    active = invitation_command()
    active.payload.template_html = active.payload.template_html.replace(
        "{{partner_claim_url}}", "https://api.camel-hub.com/"
    )
    active.payload.template_text = active.payload.template_text.replace(
        "{{partner_claim_url}}", "https://api.camel-hub.com/"
    )
    output = parse_generation(copy_payload(), active, active.payload.targets[0])
    assert "{{partner_claim_url}}" not in output.html
    assert "{{partner_claim_url}}" not in output.text


def test_research_generation_rejects_one_sided_partner_placeholder() -> None:
    active = invitation_command()
    active.payload.template_html = active.payload.template_html.replace(
        "{{partner_claim_url}}", "https://api.camel-hub.com/"
    )
    with pytest.raises(ValueError, match="partner_claim_url"):
        parse_generation(copy_payload(), active, active.payload.targets[0])


def test_generic_generation_must_keep_optional_partner_placeholder_in_both_formats() -> None:
    active = command(1)
    active.payload.template_html += '<a href="{{partner_claim_url}}">Activate</a>'
    active.payload.template_text += "\nActivate: {{partner_claim_url}}"
    generated = {
        "subject": "Research invitation",
        "html": (
            '<a href="{{partner_claim_url}}">Activate</a>'
            '<a href="{{unsubscribe_url}}">Unsubscribe</a>'
        ),
        "text": "Activate: {{partner_claim_url}}\nUnsubscribe: {{unsubscribe_url}}",
        "rationale": "Grounded in the public abstract.",
    }
    assert parse_generation(generated, active, active.payload.targets[0]).html == generated["html"]
    with pytest.raises(ValueError, match="partner_claim_url"):
        parse_generation(
            {**generated, "text": "Unsubscribe: {{unsubscribe_url}}"},
            active,
            active.payload.targets[0],
        )


@pytest.mark.parametrize("field", ["subject", "rationale"])
def test_generic_generation_rejects_partner_claim_placeholder_in_metadata(field: str) -> None:
    active = command(1)
    generated = {
        "subject": "Research invitation",
        "html": '<a href="{{unsubscribe_url}}">Unsubscribe</a>',
        "text": "Unsubscribe: {{unsubscribe_url}}",
        "rationale": "Grounded in the public abstract.",
    }
    generated[field] = "{{partner_claim_url}}"
    with pytest.raises(ValidationError, match="partner_claim_url"):
        parse_generation(generated, active, active.payload.targets[0])


@pytest.mark.parametrize("promise", ["10 API token credits", "5 model API credits"])
def test_research_generation_rejects_intervening_credit_unit_words(promise: str) -> None:
    active = invitation_command()
    prose = copy_payload()
    prose["collaborationIdea"] = f"We could offer {promise} for the proposed experiment."
    with pytest.raises(ValidationError, match="credit amount"):
        parse_generation(prose, active, active.payload.targets[0])


def test_unknown_author_has_neutral_greeting_citation_and_private_ai_input() -> None:
    active = invitation_command()
    target = PersonalizationTarget.model_validate(
        {**active.payload.targets[0].model_dump(), "author_name": None, "organization": None}
    )
    prose = {**copy_payload(), "researchConnection": (
        "The cited abstract describes evaluating tool selection by AI agents. "
        "This evaluation setup suggests a useful starting point for a joint study."
    )}
    output = parse_generation(prose, active, target)
    for body in (output.html, output.text):
        assert "Hello research team," in body
        assert "your publicly listed research" not in body
        assert "{{unsubscribe_url}}" in body
        assert "None" not in body
    ai_input = public_generation_input(active, target)
    assert "author" not in ai_input
    assert "do not" in str(ai_input).lower()
    assert "authorship" in str(ai_input).lower()
    assert "recipientId" not in json.dumps(ai_input)


@pytest.mark.parametrize("author", ["", "   "])
def test_unknown_author_must_be_explicit_null_not_blank(author: str) -> None:
    with pytest.raises(ValidationError):
        PersonalizationTarget.model_validate(
            {**invitation_command().payload.targets[0].model_dump(), "author_name": author}
        )


def test_renders_ai_prose_inside_branded_html_and_equivalent_plain_text() -> None:
    active = invitation_command()
    generated = parse_generation(copy_payload(), active, active.payload.targets[0])
    assert generated.subject == copy_payload()["subject"]
    assert 'role="presentation"' in generated.html
    assert 'style="' in generated.html
    assert "https://api.camel-hub.com/" in generated.html
    assert "https://arxiv.org/abs/2608.00001" in generated.html
    for value in (copy_payload()["researchConnection"], copy_payload()["collaborationIdea"]):
        assert value in generated.html
        assert value in generated.text
    for value in (generated.html, generated.text):
        assert value.count("{{unsubscribe_url}}") == 1
        assert "{{paper_title}}" not in value
        assert "{{author_name}}" not in value
        assert "subject to separate agreement on scope, resources, and terms" in value
    assert "script" not in generated.html
    assert "<img" not in generated.html


def test_preserves_operator_layout_and_copy_edits_and_escapes_public_metadata() -> None:
    active = invitation_command()
    active.payload.template_html = active.payload.template_html.replace(
        "A research idea, together.", "Let us explore a research question."
    ).replace("#183d33", "#102030")
    target = PersonalizationTarget.model_validate(
        {
            **active.payload.targets[0].model_dump(),
            "author_name": 'Ada <img src=x onerror="bad"> & Bob',
            "paper_title": 'Tools & "Agents" <script>bad()</script>',
        }
    )
    output = parse_generation(copy_payload(), active, target)
    assert "Let us explore a research question." in output.html
    assert "Let us explore a research question." in output.text
    assert "#102030" in output.html
    assert "&lt;img" in output.html
    assert "&lt;script&gt;" in output.html
    assert "<script>" not in output.html
    assert "<img" not in output.html
    assert target.paper_title in output.text


def test_handles_nested_slot_formatting_without_retaining_old_copy() -> None:
    active = invitation_command()
    active.payload.template_html = active.payload.template_html.replace(
        f'<span title="{CONNECTION_SLOT}">',
        f'<span title="{CONNECTION_SLOT}"><span><strong>Old nested copy</strong></span>',
    )
    output = parse_generation(copy_payload(), active, active.payload.targets[0])
    assert "Old nested copy" not in output.html
    assert copy_payload()["researchConnection"] in output.html


@pytest.mark.parametrize("mutation", ["remove", "duplicate"])
def test_changed_or_duplicated_ai_slot_fails_instead_of_publishing_generic_copy(
    mutation: str,
) -> None:
    active = invitation_command()
    if mutation == "remove":
        active.payload.template_html = active.payload.template_html.replace(
            CONNECTION_SLOT, "other"
        )
    else:
        active.payload.template_html += f'<span title="{CONNECTION_SLOT}">Duplicate</span>'
    with pytest.raises(ValueError, match="exactly one"):
        parse_generation(copy_payload(), active, active.payload.targets[0])


@pytest.mark.parametrize(
    "unsafe",
    ["Bad\r\nBcc: other", "<b>bad</b>", "{{paper_title}}", "https://bad.invalid", "$10 credits"],
)
def test_rejects_provider_header_injection_markup_variables_and_external_links(unsafe: str) -> None:
    with pytest.raises(ValidationError):
        ResearchInvitationCopy.model_validate({**copy_payload(), "subject": unsafe})


def test_campaign_purpose_and_public_paper_context_remain_the_generation_source() -> None:
    active = invitation_command()
    active.payload.purpose = "Focus the proposal on reproducibility of agent evaluation."
    generation_input = public_generation_input(active, active.payload.targets[0])
    assert generation_input["campaignPurpose"] == active.payload.purpose
    assert generation_input["paper"] == {
        "title": "Paper 0",
        "abstract": "Public abstract 0",
        "arxivId": "2608.00001",
        "primaryCategory": "cs.AI",
        "url": "https://arxiv.org/abs/2608.00001",
    }
    serialized = json.dumps(generation_input)
    assert "recipientId" not in serialized
    assert "research@example.org" not in serialized
    assert "Do not invent findings or resource budgets" in serialized


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["anthropic", "openai"])
async def test_both_providers_request_small_structured_copy_and_return_rendered_email(
    provider: str,
) -> None:
    active = invitation_command()

    async def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        schema = (
            body["tools"][0]["input_schema"]
            if provider == "anthropic"
            else body["text"]["format"]["schema"]
        )
        assert set(schema["required"]) == {
            "subject",
            "researchConnection",
            "collaborationIdea",
            "rationale",
        }
        if provider == "anthropic":
            return httpx.Response(
                200,
                json={
                    "content": [
                        {
                            "type": "tool_use",
                            "name": "personalized_email",
                            "input": copy_payload(),
                        }
                    ]
                },
            )
        return httpx.Response(200, json={"output_text": json.dumps(copy_payload())})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        client = (
            AnthropicEmailClient(http, SecretStr("test-key"), "test-model")
            if provider == "anthropic"
            else OpenAIEmailClient(http, SecretStr("test-key"), "test-model")
        )
        output = await client.generate(active, active.payload.targets[0])
    assert "Camel Hub Research Team" in output.html
    assert copy_payload()["collaborationIdea"] in output.html
    assert "{{unsubscribe_url}}" in output.text


@pytest.mark.asyncio
async def test_anthropic_compatible_gateway_json_fallback_uses_branded_schema() -> None:
    active = invitation_command()
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            json={
                "content": [
                    {
                        "type": "text",
                        "text": "```json\n" + json.dumps(copy_payload()) + "\n```",
                    }
                ]
            },
        )
    )
    async with httpx.AsyncClient(transport=transport) as http:
        output = await AnthropicEmailClient(http, SecretStr("test"), "test").generate(
            active,
            active.payload.targets[0],
        )
    assert "A research idea, together." in output.html


@pytest.mark.asyncio
async def test_branded_gateway_does_not_fall_back_to_unstructured_email_html() -> None:
    active = invitation_command()
    generic = render_invitation(
        ResearchInvitationCopy.model_validate(copy_payload()),
        active.payload.targets[0],
    ).model_dump()
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            200,
            json={
                "content": [
                    {
                        "type": "tool_use",
                        "name": "personalized_email",
                        "input": generic,
                    }
                ]
            },
        )
    )
    async with httpx.AsyncClient(transport=transport) as http:
        with pytest.raises(TransientGenerationError) as error:
            await AnthropicEmailClient(http, SecretStr("test"), "test").generate(
                active,
                active.payload.targets[0],
            )
    assert error.value.code == "INVALID_PROVIDER_OUTPUT"
