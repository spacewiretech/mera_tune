-- Migration: add_openrouter_tts
-- generate-ringtone can render the spoken name through OpenRouter instead of Gemini directly
-- (generate-ringtone/tts.ts, provider "openrouter"): the same Gemini TTS model family, prompts,
-- attempt plan, voice and 24 kHz 16-bit mono PCM to the mixer, so the ringtone comes out the same.
--
--   openrouter_use                 'true' -> OpenRouter; anything else (default) -> Gemini.
--   openrouter_tts_model           OpenRouter model slug for the first two attempts.
--   openrouter_tts_fallback_model  Slug for the last attempt; 'none' retries on the primary.
--   openrouter_api_key             NOT seeded: add it yourself (or set the OPENROUTER_API_KEY
--                                  Edge Function secret, which wins over app_config).
--
-- app_config is read on every request, so flipping openrouter_use needs no redeploy. Seeded
-- with openrouter_use = 'false': nothing changes until it is switched on.
-- Safe to re-run: ON CONFLICT DO NOTHING.

INSERT INTO public.app_config (key, value, description) VALUES
    ('openrouter_use', 'false', 'true = generate-ringtone renders names through OpenRouter (openrouter_api_key / OPENROUTER_API_KEY); false = Gemini direct (gemini_api_key)'),
    ('openrouter_tts_model', 'google/gemini-3.1-flash-tts-preview', 'OpenRouter TTS model slug (first two attempts) when openrouter_use = true'),
    ('openrouter_tts_fallback_model', 'google/gemini-3.8-flash-tts', 'OpenRouter TTS model slug for the last attempt; none = retry on openrouter_tts_model')
ON CONFLICT (key) DO NOTHING;
