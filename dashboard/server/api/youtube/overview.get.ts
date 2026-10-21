// Live channel and video statistics plus the stored growth history.
export default defineEventHandler(async (event) => {
  const force = getQuery(event).refresh === '1'
  const [overview, history] = await Promise.all([channelOverview(force), readHistory(90)])
  return { ...overview, history }
})
