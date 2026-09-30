package dev.polimo.prompton

/** Use case documents the runtime tests fetch, cache and refuse. */
object SnapshotFixtures {
    const val PRODUCTION_ETAG: String = "\"sha256-aaaa\""
    const val UPDATED_ETAG: String = "\"sha256-bbbb\""

    fun useCaseDocument(
        environment: String = "production",
        project: String = "fixture",
        temperature: Double = 0.2,
        systemPrompt: String = "You are a friendly greeter.",
    ): String =
        """
        {
          "schema_version": 4,
          "project": "$project",
          "environment": "$environment",
          "prompts": {
            "greeting": {
              "id": "0198f2a1-0000-7000-8000-0000000000c1",
              "kind": "chat",
              "input_schema": [{"name": "name", "type": "string", "required": true}],
              "default_params": {"max_tokens": 512},
              "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144,
                                 "retention_days": 30, "encrypt": false}
            },
            "summarize": {
              "id": "0198f2a1-0000-7000-8000-0000000000c2",
              "kind": "text",
              "input_schema": [{"name": "items", "type": "list", "required": true}],
              "default_params": {},
              "payload_policy": {"mode": "full", "sample_rate": 1.0, "max_bytes": 262144}
            }
          },
          "deployments": {
            "greeting": {
              "id": "0198f2a1-0000-7000-8000-00000000d001",
              "revision": 3,
              "model_id": "0198f2a1-0000-7000-8000-00000000e001",
              "params": {"temperature": $temperature},
              "provider_options": {},
              "template_pins": {
                "default": "0198f2a1-0000-7000-8000-00000000a001",
                "ko": "0198f2a1-0000-7000-8000-00000000a002"
              }
            },
            "summarize": {
              "id": "0198f2a1-0000-7000-8000-00000000d002",
              "revision": 1,
              "model_id": "0198f2a1-0000-7000-8000-00000000e001",
              "params": {},
              "provider_options": {},
              "template_pins": {"default": "0198f2a1-0000-7000-8000-00000000a003"}
            }
          },
          "prompt_versions": {
            "0198f2a1-0000-7000-8000-00000000a001": {
              "id": "0198f2a1-0000-7000-8000-00000000a001",
              "prompt_id": "0198f2a1-0000-7000-8000-00000000b001",
              "number": 2,
              "engine": "liquid",
              "messages": [
                {"role": "system", "content": "$systemPrompt"},
                {"role": "user", "content": "Say hello to {{ name }}."}
              ],
              "text_template": null
            },
            "0198f2a1-0000-7000-8000-00000000a002": {
              "id": "0198f2a1-0000-7000-8000-00000000a002",
              "prompt_id": "0198f2a1-0000-7000-8000-00000000b002",
              "number": 4,
              "engine": "liquid",
              "messages": [
                {"role": "system", "content": "친절한 인사 도우미입니다."},
                {"role": "user", "content": "{{ name }}에게 한국어로 인사해 주세요."}
              ],
              "text_template": null
            },
            "0198f2a1-0000-7000-8000-00000000a003": {
              "id": "0198f2a1-0000-7000-8000-00000000a003",
              "prompt_id": "0198f2a1-0000-7000-8000-00000000b003",
              "number": 1,
              "engine": "liquid",
              "messages": [],
              "text_template": "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}"
            }
          },
          "models": {
            "0198f2a1-0000-7000-8000-00000000e001": {
              "id": "0198f2a1-0000-7000-8000-00000000e001",
              "provider": "openrouter",
              "model_id": "openai/gpt-4o-mini",
              "display_name": "GPT-4o mini",
              "metadata": {},
              "provider_options": {"only": ["OpenAI"]},
              "capabilities": ["tools"],
              "status": "active"
            }
          }
        }
        """.trimIndent()

    fun twoUseCases(): String = useCaseDocument()
}
