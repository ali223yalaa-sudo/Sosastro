/**
 * Authoritative star packages and gift SKUs.
 * Client may display these; buy/send always resolve price from this module.
 */

export type StarPackage = {
  id: string;
  stars: number;
  priceCents: number;
  currencyCode: "USD";
  label: string;
};

export type GiftSku = {
  id: string;
  starPrice: number;
  emoji: string;
  labelAr: string;
  labelEn: string;
};

export const STAR_PACKAGES: StarPackage[] = [
  { id: "stars_50", stars: 50, priceCents: 99, currencyCode: "USD", label: "50 Stars" },
  { id: "stars_120", stars: 120, priceCents: 199, currencyCode: "USD", label: "120 Stars" },
  { id: "stars_300", stars: 300, priceCents: 449, currencyCode: "USD", label: "300 Stars" },
  { id: "stars_800", stars: 800, priceCents: 999, currencyCode: "USD", label: "800 Stars" },
  { id: "stars_2000", stars: 2000, priceCents: 1999, currencyCode: "USD", label: "2000 Stars" },
];

export const GIFT_SKUS: GiftSku[] = [
  { id: "coffee", starPrice: 5, emoji: "☕", labelAr: "قهوة", labelEn: "Coffee" },
  { id: "tea", starPrice: 5, emoji: "🍵", labelAr: "شاي", labelEn: "Tea" },
  { id: "kiss", starPrice: 6, emoji: "💋", labelAr: "قبلة", labelEn: "Kiss" },
  { id: "heart", starPrice: 8, emoji: "❤️", labelAr: "قلب", labelEn: "Heart" },
  { id: "hug", starPrice: 9, emoji: "🤗", labelAr: "عناق", labelEn: "Hug" },
  { id: "rose", starPrice: 10, emoji: "🌹", labelAr: "وردة", labelEn: "Rose" },
  { id: "tulip", starPrice: 12, emoji: "🌷", labelAr: "توليب", labelEn: "Tulip" },
  { id: "balloon", starPrice: 14, emoji: "🎈", labelAr: "بالون", labelEn: "Balloon" },
  { id: "fire", starPrice: 15, emoji: "🔥", labelAr: "نار", labelEn: "Fire" },
  { id: "chocolate", starPrice: 16, emoji: "🍫", labelAr: "شوكولاتة", labelEn: "Chocolate" },
  { id: "sparkle", starPrice: 18, emoji: "✨", labelAr: "بريق", labelEn: "Sparkle" },
  { id: "star_gift", starPrice: 20, emoji: "⭐", labelAr: "نجمة", labelEn: "Star" },
  { id: "giftbox", starPrice: 22, emoji: "🎁", labelAr: "هدية", labelEn: "Gift box" },
  { id: "cake", starPrice: 25, emoji: "🎂", labelAr: "كيكة", labelEn: "Cake" },
  { id: "moon", starPrice: 28, emoji: "🌙", labelAr: "قمر", labelEn: "Moon" },
  { id: "sun", starPrice: 28, emoji: "☀️", labelAr: "شمس", labelEn: "Sun" },
  { id: "rocket", starPrice: 30, emoji: "🚀", labelAr: "صاروخ", labelEn: "Rocket" },
  { id: "rainbow", starPrice: 32, emoji: "🌈", labelAr: "قوس قزح", labelEn: "Rainbow" },
  { id: "gem", starPrice: 35, emoji: "💎", labelAr: "جوهرة", labelEn: "Gem" },
  { id: "fireworks", starPrice: 38, emoji: "🎆", labelAr: "ألعاب نارية", labelEn: "Fireworks" },
  { id: "bouquet", starPrice: 40, emoji: "💐", labelAr: "باقة", labelEn: "Bouquet" },
  { id: "medal", starPrice: 45, emoji: "🏅", labelAr: "وسام", labelEn: "Medal" },
  { id: "shield", starPrice: 50, emoji: "🛡️", labelAr: "درع", labelEn: "Shield" },
  { id: "trophy", starPrice: 60, emoji: "🏆", labelAr: "كأس", labelEn: "Trophy" },
  { id: "ring", starPrice: 70, emoji: "💍", labelAr: "خاتم", labelEn: "Ring" },
  { id: "diamond", starPrice: 80, emoji: "💠", labelAr: "ماسة", labelEn: "Diamond" },
  { id: "unicorn", starPrice: 85, emoji: "🦄", labelAr: "يونيكورن", labelEn: "Unicorn" },
  { id: "castle", starPrice: 90, emoji: "🏰", labelAr: "قلعة", labelEn: "Castle" },
  { id: "crown", starPrice: 100, emoji: "👑", labelAr: "تاج", labelEn: "Crown" },
  { id: "lion", starPrice: 110, emoji: "🦁", labelAr: "أسد", labelEn: "Lion" },
  { id: "dragon", starPrice: 120, emoji: "🐉", labelAr: "تنين", labelEn: "Dragon" },
  { id: "phoenix", starPrice: 150, emoji: "🔥🦅", labelAr: "عنقاء", labelEn: "Phoenix" },
  { id: "car", starPrice: 200, emoji: "🏎️", labelAr: "سيارة", labelEn: "Car" },
];

const GIFT_BY_ID = new Map(GIFT_SKUS.map((s) => [s.id, s]));
const PACK_BY_ID = new Map(STAR_PACKAGES.map((s) => [s.id, s]));

export function findGiftSku(id: string): GiftSku | undefined {
  if (!id || id.length > 32) return undefined;
  return GIFT_BY_ID.get(id);
}

export function findStarPackage(id: string): StarPackage | undefined {
  if (!id || id.length > 40) return undefined;
  return PACK_BY_ID.get(id);
}

/** Server-only daily gift. Client cannot choose this amount. */
export const DAILY_STARS = 10;

export function starsCatalogPublic() {
  return {
    checkoutEnabled: false,
    provider: "pending",
    dailyGift: {
      stars: DAILY_STARS,
      period: "utc_day",
    },
    packages: STAR_PACKAGES.map((p) => ({
      id: p.id,
      stars: p.stars,
      priceCents: p.priceCents,
      currencyCode: p.currencyCode,
      label: p.label,
    })),
    gifts: GIFT_SKUS.map((g) => ({
      id: g.id,
      starPrice: g.starPrice,
      emoji: g.emoji,
      labelAr: g.labelAr,
      labelEn: g.labelEn,
    })),
  };
}
