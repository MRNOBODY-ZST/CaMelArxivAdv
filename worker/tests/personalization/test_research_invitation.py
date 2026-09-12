from __future__ import annotations

import json

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
        assert "scope, resources, and terms would be worked out together" in value
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
    "unsafe", ["Bad\r\nBcc: other", "<b>bad</b>", "{{paper_title}}", "https://bad.invalid"]
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
