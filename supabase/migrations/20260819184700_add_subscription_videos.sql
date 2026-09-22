-- Public conversion videos for the subscription paywall, keyed by app locale.

CREATE TABLE IF NOT EXISTS public.subscription_videos (
    locale_code TEXT PRIMARY KEY,
    language_name TEXT NOT NULL,
    video_url TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.subscription_videos ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Anon read active subscription videos"
    ON public.subscription_videos
    FOR SELECT
    TO anon
    USING (is_active = TRUE);

CREATE POLICY "Public read active subscription videos"
    ON public.subscription_videos
    FOR SELECT
    TO authenticated
    USING (is_active = TRUE);

REVOKE ALL ON public.subscription_videos FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.subscription_videos TO anon, authenticated;

INSERT INTO public.subscription_videos (locale_code, language_name, video_url)
VALUES
    ('en', 'English', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('hi', 'Hindi', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('te', 'Telugu', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('ta', 'Tamil', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('kn', 'Kannada', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('ml', 'Malayalam', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('mr', 'Marathi', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('or', 'Odia', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4'),
    ('bn', 'Bengali', 'https://meratune.app/ads/new%20convertion%20video%20-%20meratune.mp4')
ON CONFLICT (locale_code) DO NOTHING;
