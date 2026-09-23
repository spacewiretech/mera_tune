-- Gemini TTS fallback model for generate-ringtone's last attempt (see generate-ringtone/tts.ts,
-- TTS_ATTEMPT_PLAN). The 2.5 flash TTS preview often returns no audio for a one-word name; the
-- 3.1 flash TTS preview was reliable in testing but costs about twice as much, so it is only used
-- after two attempts on the primary model failed. Set the value to 'none' to disable it.
INSERT INTO public.app_config (key, value, description) VALUES
    ('gemini_tts_fallback_model', 'gemini-3.1-flash-tts-preview',
     'Gemini TTS model for the last attempt when gemini_tts_model returned no usable audio; none = disabled')
ON CONFLICT (key) DO NOTHING;
