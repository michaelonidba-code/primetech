// Prints a user entry for NUXT_AUTH_USERS. Usage: npm run hash-password -- <user>
// The password is read from stdin, never argv (other processes can read argv). On a terminal
// it is not echoed.
import { createInterface } from 'node:readline'
import { hashPassword } from '../server/utils/auth.ts'

const user = process.argv[2]
if (!user || !/^[A-Za-z0-9._-]{1,64}$/.test(user)) {
  console.error('usage: npm run hash-password -- <user>   (letters, digits, . _ -)')
  process.exit(1)
}

function done(password: string) {
  if (password.length < 12) {
    console.error('Use at least 12 characters.')
    process.exit(1)
  }
  console.log(`${user}=${hashPassword(password)}`)
  process.exit(0)
}

if (process.stdin.isTTY) {
  process.stderr.write(`Password for ${user}: `)
  process.stdin.setRawMode(true)
  process.stdin.setEncoding('utf8')
  let password = ''
  process.stdin.on('data', (chunk: string) => {
    for (const ch of chunk) {
      if (ch === '\r' || ch === '\n') {
        process.stdin.setRawMode(false)
        process.stderr.write('\n')
        done(password)
      } else if (ch === '\u0003') {
        process.stdin.setRawMode(false)
        process.exit(130)
      } else if (ch === '\u007f' || ch === '\b') {
        password = password.slice(0, -1)
      } else {
        password += ch
      }
    }
  })
} else {
  const rl = createInterface({ input: process.stdin })
  rl.once('line', (line) => {
    rl.close()
    done(line)
  })
}
