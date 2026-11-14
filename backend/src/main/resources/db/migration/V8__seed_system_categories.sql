-- ==============================================================================
-- InboxIQ - Migration V8: Idempotent Seeding of 11 Built-in System Categories
-- Uses stable UUIDs to enable deterministic code constants and repeatable migration.
-- ==============================================================================

INSERT INTO categories (
    id, name, slug, description, is_system, owner_user_id, icon, color, display_order, is_active
)
VALUES
(
    '10000000-0000-0000-0000-000000000001',
    'Career & Jobs',
    'career-jobs',
    'Job offers, interview requests, recruiter outreach, hiring assessments, and career networking.',
    true,
    NULL,
    'Briefcase',
    'blue',
    1,
    true
),
(
    '10000000-0000-0000-0000-000000000002',
    'Creator & Brand Promotions',
    'creator-brand',
    'Sponsorship offers, affiliate partnerships, brand collaborations, and media outreach.',
    true,
    NULL,
    'Sparkles',
    'purple',
    2,
    true
),
(
    '10000000-0000-0000-0000-000000000003',
    'Sports',
    'sports',
    'Match updates, tournament schedules, athletic competition results, and sports tickets.',
    true,
    NULL,
    'Trophy',
    'emerald',
    3,
    true
),
(
    '10000000-0000-0000-0000-000000000004',
    'Education',
    'education',
    'Course enrollments, admissions, academic events, certifications, and exam notifications.',
    true,
    NULL,
    'GraduationCap',
    'indigo',
    4,
    true
),
(
    '10000000-0000-0000-0000-000000000005',
    'Finance & Banking',
    'finance-banking',
    'Bank statements, billing alerts, invoices, tax updates, and investment notifications.',
    true,
    NULL,
    'Landmark',
    'amber',
    5,
    true
),
(
    '10000000-0000-0000-0000-000000000006',
    'Shopping & Offers',
    'shopping-offers',
    'E-commerce order receipts, shipping confirmations, discount coupons, and promotional deals.',
    true,
    NULL,
    'ShoppingBag',
    'rose',
    6,
    true
),
(
    '10000000-0000-0000-0000-000000000007',
    'Events & Invitations',
    'events-invitations',
    'Calendar invites, conference registrations, meetups, RSVP notices, and webinar links.',
    true,
    NULL,
    'Calendar',
    'teal',
    7,
    true
),
(
    '10000000-0000-0000-0000-000000000008',
    'News & Updates',
    'news-updates',
    'Industry newsletters, curated editorial digests, regulatory announcements, and press releases.',
    true,
    NULL,
    'Newspaper',
    'slate',
    8,
    true
),
(
    '10000000-0000-0000-0000-000000000009',
    'Technology & Product Updates',
    'tech-updates',
    'Software releases, SDK changelogs, developer platform alerts, and product feature announcements.',
    true,
    NULL,
    'Code',
    'cyan',
    9,
    true
),
(
    '10000000-0000-0000-0000-000000000010',
    'Travel & Bookings',
    'travel-bookings',
    'Flight itineraries, hotel confirmations, boarding passes, train bookings, and car rentals.',
    true,
    NULL,
    'Plane',
    'sky',
    10,
    true
),
(
    '10000000-0000-0000-0000-000000000011',
    'Personal & Important Communications',
    'personal-important',
    'Direct personal correspondence, legal notices, urgent non-automated communications, and direct inquiries.',
    true,
    NULL,
    'Mail',
    'violet',
    11,
    true
)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    slug = EXCLUDED.slug,
    description = EXCLUDED.description,
    icon = EXCLUDED.icon,
    color = EXCLUDED.color,
    display_order = EXCLUDED.display_order,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;

