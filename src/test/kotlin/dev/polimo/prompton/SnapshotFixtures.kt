package dev.polimo.prompton

/** Prompt documents the runtime tests fetch, cache and refuse. */
object SnapshotFixtures {
    const val PRODUCTION_ETAG: String = "\"sha256-aaaa\""
    const val UPDATED_ETAG: String = "\"sha256-bbbb\""

    fun promptDocument(
        environment: String = "production",
        project: String = "fixture",
        temperature: Double = 0.2,
        systemPrompt: String = "You are a friendly greeter.",
    ): String =
        """
        {
          "schema_version": 1,
          "project": "$project",
          "environment": "$environment",
          "prompts": {
            "greeting": {
              "kind": "chat",
              "default_params": {
                "max_tokens": 512
              },
              "payload_policy": {
                "mode": "full",
                "sample_rate": 1.0,
                "max_bytes": 262144,
                "retention_days": 30,
                "encrypt": false
              },
              "variables": [
                {
                  "name": "name",
                  "type": "string",
                  "required": true
                }
              ],
              "key": "greeting"
            },
            "summarize": {
              "kind": "text",
              "default_params": {},
              "payload_policy": {
                "mode": "full",
                "sample_rate": 1.0,
                "max_bytes": 262144
              },
              "variables": [
                {
                  "name": "items",
                  "type": "list",
                  "required": true
                }
              ],
              "key": "summarize"
            }
          },
          "deployments": {
            "greeting": {
              "revision": "v2026.09.30-3",
              "params": {
                "temperature": $temperature
              },
              "provider_options": {},
              "provider": "openrouter",
              "model": "openai/gpt-4o-mini",
              "prompt_version": 2
            },
            "summarize": {
              "revision": "v2026.09.30-1",
              "params": {},
              "provider_options": {},
              "provider": "openrouter",
              "model": "openai/gpt-4o-mini",
              "prompt_version": 1
            }
          },
          "prompt_versions": {
            "greeting@2": {
              "number": 2,
              "engine": "liquid",
              "messages": [
                {
                  "role": "system",
                  "content": "$systemPrompt"
                },
                {
                  "role": "user",
                  "content": "Say hello to {{ name }}."
                }
              ],
              "text_template": null,
              "prompt": "greeting",
              "kind": "chat"
            },
            "summarize@1": {
              "number": 1,
              "engine": "liquid",
              "messages": [],
              "text_template": "Summarize:\\n{% for item in items %}- {{ item }}\\n{% endfor %}",
              "prompt": "summarize",
              "kind": "text"
            }
          },
          "models": {
            "openrouter:openai/gpt-4o-mini": {
              "provider": "openrouter",
              "display_name": "GPT-4o mini",
              "metadata": {},
              "provider_options": {
                "only": [
                  "OpenAI"
                ]
              },
              "capabilities": [
                "tools"
              ],
              "status": "active",
              "model": "openai/gpt-4o-mini"
            }
          }
        }
        """.trimIndent()

    fun twoPrompts(): String = promptDocument()
}
