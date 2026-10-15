import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createSession, hashPassword, parseUsers, RateLimiter, verifyPassword, verifySession } from '../server/utils/auth.ts'

const SECRET = 'test-secret-that-is-at-least-32-characters'
const HOUR = 3_600_000

test('passwords verify only against their own hash', () => {
  const hash = hashPassword('correct horse')
  assert.ok(verifyPassword('correct horse', hash))
  assert.ok(!verifyPassword('correct horse ', hash))
  assert.ok(!verifyPassword('wrong', hash))
  assert.ok(!verifyPassword('correct horse', 'scrypt.1.1.1.x.y'), 'malformed hash')
  assert.notEqual(hashPassword('same'), hashPassword('same'), 'salted')
})

test('hashes survive a .env file: no $, : or , characters', () => {
  assert.doesNotMatch(hashPassword('pw'), /[$:,=]/)
})

test('parses users and reports malformed entries without revealing them', () => {
  const alice = hashPassword('a')
  const { users, errors } = parseUsers(` alice=${alice}, bad entry ,bob:${alice}`)
  assert.deepEqual([...users.keys()], ['alice'])
  assert.equal(errors.length, 2)
  assert.ok(errors.every((e) => !e.includes('scrypt')))
})

test('sessions are valid until they expire', () => {
  const hash = hashPassword('pw')
  const users = new Map([['alice', hash]])
  const t0 = 1_800_000_000_000
  const token = createSession('alice', hash, SECRET, 12 * HOUR, t0)
  assert.equal(verifySession(token, users, SECRET, t0 + HOUR), 'alice')
  assert.equal(verifySession(token, users, SECRET, t0 + 12 * HOUR), null)
})

test('tampered, foreign or orphaned sessions are rejected', () => {
  const hash = hashPassword('pw')
  const users = new Map([['alice', hash], ['bob', hashPassword('pw2')]])
  const token = createSession('alice', hash, SECRET, HOUR)
  const [body, mac] = token.split('.')
  const forged = Buffer.from(JSON.stringify({ u: 'bob', exp: Date.now() + HOUR, v: 'x' })).toString('base64url')

  assert.equal(verifySession(`${forged}.${mac}`, users, SECRET), null, 'body swapped')
  assert.equal(verifySession(`${body}.${mac}x`, users, SECRET), null, 'mac altered')
  assert.equal(verifySession(token, users, 'another-secret-another-secret-123'), null, 'other secret')
  assert.equal(verifySession(token, new Map(), SECRET), null, 'user removed')
  assert.equal(verifySession('', users, SECRET), null)
  assert.equal(verifySession('garbage', users, SECRET), null)
})

test('changing a password ends existing sessions', () => {
  const old = hashPassword('old')
  const token = createSession('alice', old, SECRET, HOUR)
  assert.equal(verifySession(token, new Map([['alice', old]]), SECRET), 'alice')
  assert.equal(verifySession(token, new Map([['alice', hashPassword('new')]]), SECRET), null)
})

test('rate limiter blocks after the limit until the window passes', () => {
  const limiter = new RateLimiter(3, 60_000)
  for (let i = 0; i < 3; i++) {
    assert.ok(limiter.allowed('ip', 0))
    limiter.record('ip', 0)
  }
  assert.ok(!limiter.allowed('ip', 1_000))
  assert.ok(limiter.allowed('other', 1_000))
  assert.ok(limiter.allowed('ip', 60_000))
  limiter.reset('ip')
  assert.ok(limiter.allowed('ip', 1_000))
})
