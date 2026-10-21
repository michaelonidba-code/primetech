// Sponsor campaigns with their live state, limits, and the snippet for the publisher site.
export default defineEventHandler(async () => {
  const list = await readSponsors()
  return {
    campaigns: list.map(sponsorWithState).sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    countries: SPONSOR_COUNTRIES,
    limits: sponsorLimits(),
    today: sponsorToday(),
  }
})
