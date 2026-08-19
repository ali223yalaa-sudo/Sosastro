/**
 * Server-side price catalog. Client mirrors for display only —
 * purchase always resolves price from this module.
 */
export type ItemKind = "dice" | "piece" | "board";
export type Currency = "gold" | "gems";

export type CatalogSku = {
  kind: ItemKind;
  id: string;
  currency: Currency;
  /** 0 = free starter unlock. */
  price: number;
};

export const DAILY_GOLD = 100;
export const DAILY_GEMS = 5;

export const CATALOG: CatalogSku[] = [
  // Dice — classic free
  { kind: "dice", id: "cdr_classic", currency: "gold", price: 0 },
  { kind: "dice", id: "cdr_imperial", currency: "gold", price: 0 },
  { kind: "dice", id: "cdr_classic_neon", currency: "gold", price: 120 },
  { kind: "dice", id: "cdr_nested", currency: "gold", price: 150 },
  { kind: "dice", id: "cdr_nested_lag", currency: "gold", price: 180 },
  { kind: "dice", id: "cdr_nested_shiny", currency: "gems", price: 12 },
  { kind: "dice", id: "cdr_nested_crystal", currency: "gems", price: 15 },
  { kind: "dice", id: "cdr_wire", currency: "gems", price: 18 },
  { kind: "dice", id: "cdr_ruby", currency: "gems", price: 22 },
  { kind: "dice", id: "cdr_gold", currency: "gems", price: 25 },
  { kind: "dice", id: "cdr_midnight", currency: "gems", price: 20 },
  // Pieces — disc free
  { kind: "piece", id: "disc", currency: "gold", price: 0 },
  { kind: "piece", id: "football", currency: "gold", price: 200 },
  { kind: "piece", id: "basketball", currency: "gold", price: 200 },
  { kind: "piece", id: "egg", currency: "gems", price: 10 },
  { kind: "piece", id: "snake", currency: "gems", price: 14 },
  // Boards — ice free
  { kind: "board", id: "ice", currency: "gold", price: 0 },
  { kind: "board", id: "forest", currency: "gold", price: 250 },
  { kind: "board", id: "midnight", currency: "gems", price: 16 },
  { kind: "board", id: "oasis", currency: "gems", price: 16 },
];

export function findSku(kind: string, id: string): CatalogSku | undefined {
  return CATALOG.find((s) => s.kind === kind && s.id === id);
}

export function freeSkus(): CatalogSku[] {
  return CATALOG.filter((s) => s.price === 0);
}

export function utcDay(nowSec: number): string {
  return new Date(nowSec * 1000).toISOString().slice(0, 10);
}
