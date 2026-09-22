-- Home category chips are ordered by this column (lower = earlier).
-- New categories default to 100 so they appear at the end until sorted.

ALTER TABLE public.category
    ADD COLUMN IF NOT EXISTS sort_order integer NOT NULL DEFAULT 100;

UPDATE public.category
SET sort_order = CASE name
    WHEN 'Bollywood' THEN 1
    WHEN 'Devotional' THEN 2
    WHEN 'Romantic' THEN 3
    WHEN 'Family' THEN 4
    WHEN 'Name' THEN 5
    WHEN 'Patriotic' THEN 6
    WHEN 'Notification' THEN 7
    ELSE sort_order
END;
