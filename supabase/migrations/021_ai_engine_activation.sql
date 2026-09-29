-- 021 · AI ENGINE ACTIVATION (master prompt: AUDIT → FIX → OPTIMIZE → INTEGRATE)
--
-- AUDIT RESULT (client ai/ package): real MediaPipe LLM runtime adapter,
-- versioned model manager, capability ladder, orchestrator with validator +
-- deterministic fallback were ALL present; the ladder ended at catalog rows
-- whose url was always NULL (SmolLM2 artifacts never published; Gemma is
-- license-gated behind a HuggingFace 401). So the local LLM could never be
-- reached and everything fell through to the rules engine — by design, but
-- the catalog was honest-empty.
--
-- THIS MIGRATION: activate the ladder with REAL, publicly resolvable bundles
-- (Google litert-community, Qwen2.5 org, verified byte counts + sha256 pins
-- computed from the actual files). Existing rows are PRESERVED (§15: no
-- Gemma/Qwen/SmolLM row is deleted — dead rows carry their honest reason in
-- `source`). SmolLM2-135m tiers stay reserved until a real converter artifact
-- exists; nothing is faked.
--
-- Flags change: local_ai_enabled = TRUE. The ladder still protects low-end
-- devices (TINY/SMALL/NONE tiers find no eligible bundle url → deterministic
-- rules engine), inference never downloads implicitly (client now requires
-- an explicit GET on the AI status page, wi-fi honoured, storage pre-flight),
-- and the validator rejects any unsafe model output to the same fallback.

update engine_config
set value = value - 'ai' || jsonb_build_object('ai', jsonb_build_object(
  'local_ai_enabled', true,
  'quest_ai_enabled', true,
  'nutrition_ai_enabled', true,
  'assistant_enabled', true,
  'routine_ai_enabled', true,
  'max_context_tokens', 1024,
  'max_output_tokens', 220,
  'inference_timeout_ms', 15000,
  'unload_after_ms', 30000,
  'min_free_storage_mb', 700,
  'nutrition_daily_calorie_floor', 1600,
  'models', jsonb_build_array(
    jsonb_build_object(
      'id', 'smollm2-135m-instruct',
      'params_m', 135, 'quant', 'INT8', 'size_mb', 140, 'ram_mb', 350,
      'ctx', 1024, 'tier', 'TINY', 'backend', 'MEDIAPIPE_TASK', 'version', '0',
      'url', null, 'sha256', null,
      'source', 'PRESERVED — catalog reserved; MediaPipe converter artifact never published (no fake download)'
    ),
    jsonb_build_object(
      'id', 'smollm2-360m-instruct',
      'params_m', 360, 'quant', 'INT8', 'size_mb', 370, 'ram_mb', 700,
      'ctx', 1536, 'tier', 'SMALL', 'backend', 'MEDIAPIPE_TASK', 'version', '0',
      'url', null, 'sha256', null,
      'source', 'PRESERVED — catalog reserved; artifact never published'
    ),
    jsonb_build_object(
      'id', 'gemma-3-1b-it',
      'params_m', 1000, 'quant', 'INT4', 'size_mb', 1300, 'ram_mb', 2100,
      'ctx', 2048, 'tier', 'PLUS', 'backend', 'MEDIAPIPE_TASK', 'version', '0',
      'url', null, 'sha256', null,
      'source', 'PRESERVED — litert-community/Gemma3-1B-IT (license-gated source; device fetch would 401)'
    ),
    jsonb_build_object(
      'id', 'qwen2.5-0.5b-instruct-q8',
      'params_m', 494, 'quant', 'Q8', 'size_mb', 521, 'size_bytes', 546660344,
      'ram_mb', 950, 'ctx', 1280, 'tier', 'MID', 'backend', 'MEDIAPIPE_TASK',
      'version', '1',
      'url', 'https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task',
      'sha256', 'e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2',
      'wifi_required', true,
      'source', 'litert-community/Qwen2.5-0.5B-Instruct (Google LiteRT org, public)'
    ),
    jsonb_build_object(
      'id', 'qwen2.5-1.5b-instruct-q8',
      'params_m', 1543, 'quant', 'Q8', 'size_mb', 1524, 'size_bytes', 1597913616,
      'ram_mb', 2600, 'ctx', 1280, 'tier', 'PLUS', 'backend', 'MEDIAPIPE_TASK',
      'version', '1',
      'url', 'https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task',
      'sha256', '8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0',
      'wifi_required', true,
      'source', 'litert-community/Qwen2.5-1.5B-Instruct (Google LiteRT org, public)'
    )
  )
))
where key = 'engine';
