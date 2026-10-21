// Demand Gen and Video campaigns in the connected Google Ads account, last 30 days.
export default defineEventHandler(async () => {
  const [account, campaigns] = await Promise.all([adsAccount(), adsCampaigns()])
  return { account, campaigns }
})
