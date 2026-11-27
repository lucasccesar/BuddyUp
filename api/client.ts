import type { components, operations } from './generated/schema.js'

type GamePage = components['schemas']['GamePage']
type GameQuery = NonNullable<operations['listGames']['parameters']['query']>

export async function listGames(
  baseUrl: string,
  query: GameQuery = {},
  fetcher = fetch,
): Promise<GamePage> {
  const parameters = []
  if (query.cursor !== undefined) parameters.push(`cursor=${encodeURIComponent(query.cursor)}`)
  if (query.limit !== undefined) parameters.push(`limit=${query.limit}`)
  const suffix = parameters.length ? `?${parameters.join('&')}` : ''

  const response = await fetcher(`${baseUrl.replace(/\/+$/, '')}/v1/games${suffix}`, {
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) throw new Error(`Failed to list games (HTTP ${response.status})`)
  return response.json()
}
