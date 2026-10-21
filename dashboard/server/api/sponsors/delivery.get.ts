// Impressions, completes, clicks and spend per sponsor campaign (Druid, invalid traffic excluded).
export default defineEventHandler((event) => sponsorDelivery(Number(getQuery(event).days) || 30))
