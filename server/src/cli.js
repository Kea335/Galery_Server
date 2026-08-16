#!/usr/bin/env node
/**
 * Server-side account and device management.
 *
 * The first account has to come from somewhere, and it should not come from a
 * web form on a box that is deliberately not exposed to the internet (§13).
 *
 *   node src/cli.js user add hasan
 *   node src/cli.js user passwd hasan
 *   node src/cli.js user list
 *   node src/cli.js device list
 *   node src/cli.js device revoke <id>
 *
 * Passwords are read from the terminal, not from an argument — an argument
 * ends up in shell history and in `ps` output for every user on the machine.
 */
import readline from 'node:readline'
import { openDb } from './db.js'
import { createUser, listUsers, setPassword } from './users.js'

/**
 * Declared up here on purpose: the command runs at the top level of this
 * module, before anything further down has been evaluated, so a `let` below
 * would still be in its temporal dead zone when the first prompt reads it.
 */
let pipedLines = null
let pipedIndex = 0

const [, , group, action, ...rest] = process.argv

const commands = {
  'user add': addUser,
  'user passwd': changePassword,
  'user list': showUsers,
  'device list': showDevices,
  'device revoke': revokeDevice,
}

const command = commands[`${group} ${action}`]

if (!command) {
  usage()
  process.exit(1)
}

const db = openDb()
try {
  await command(...rest)
} catch (error) {
  console.error(`\n  ${error.message}\n`)
  process.exitCode = 1
} finally {
  db.close()
}

// ─── Commands ───────────────────────────────────────────────────────────────

async function addUser(username) {
  if (!username) throw new Error('Usage: node src/cli.js user add <username>')

  const password = await readPasswordTwice()
  const user = await createUser(db, username, password)

  console.log(`\n  Created ${user.username}.`)
  console.log('  Sign in from the app with the server address, this name and that password.\n')
}

async function changePassword(username) {
  if (!username) throw new Error('Usage: node src/cli.js user passwd <username>')

  const password = await readPasswordTwice()
  if (!(await setPassword(db, username, password))) {
    throw new Error(`No such user: ${username}`)
  }

  console.log(`\n  Password changed for ${username}.`)
  console.log('  Devices already signed in keep working; revoke them if that is not what you want.\n')
}

async function showUsers() {
  const users = listUsers(db)
  if (users.length === 0) {
    console.log('\n  No accounts yet. Create one with: node src/cli.js user add <name>\n')
    return
  }
  console.log('')
  for (const user of users) {
    console.log(`  ${user.username.padEnd(20)} created ${new Date(user.created_at).toISOString()}`)
  }
  console.log('')
}

async function showDevices() {
  const devices = db
    .prepare(
      `SELECT d.id, d.name, d.created_at, d.last_seen_at, d.revoked, u.username
       FROM devices d LEFT JOIN users u ON u.id = d.user_id
       ORDER BY d.last_seen_at DESC`,
    )
    .all()

  if (devices.length === 0) {
    console.log('\n  No devices have signed in yet.\n')
    return
  }

  console.log('')
  for (const device of devices) {
    const seen = device.last_seen_at ? new Date(device.last_seen_at).toISOString() : 'never'
    const state = device.revoked ? 'revoked' : 'active'
    console.log(
      `  ${device.id}  ${(device.username ?? '-').padEnd(12)} ` +
        `${device.name.padEnd(24)} ${state.padEnd(8)} last seen ${seen}`,
    )
  }
  console.log('')
}

async function revokeDevice(id) {
  if (!id) throw new Error('Usage: node src/cli.js device revoke <id>')
  const result = db.prepare('UPDATE devices SET revoked = 1 WHERE id = ?').run(id)
  if (result.changes === 0) throw new Error(`No such device: ${id}`)
  console.log(`\n  Revoked ${id}. That phone will be asked to sign in again.\n`)
}

// ─── Input ──────────────────────────────────────────────────────────────────

async function readPasswordTwice() {
  const first = await readHidden('  Password: ')
  if (first.length < 8) throw new Error('Password must be at least 8 characters.')

  const second = await readHidden('  Repeat:   ')
  if (first !== second) throw new Error('The two passwords do not match.')

  return first
}

/**
 * Piped input is drained once and served line by line. A fresh readline per
 * prompt does not work: stdin is a single stream, so the second reader finds it
 * already at the end and hands back nothing.
 */
async function readPipedLine() {
  if (pipedLines === null) {
    const chunks = []
    for await (const chunk of process.stdin) chunks.push(chunk)
    pipedLines = Buffer.concat(chunks).toString('utf8').split(/\r?\n/)
  }
  return pipedLines[pipedIndex++] ?? ''
}

/**
 * Reads a line without echoing it. When stdin is not a terminal the value comes
 * from the pipe — scripted setup works, it just cannot be masked.
 */
function readHidden(prompt) {
  if (!process.stdin.isTTY) return readPipedLine()

  return new Promise((resolve, reject) => {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
    const eraseLine = `${String.fromCharCode(27)}[2K\r`

    const repaint = (chunk) => {
      const value = String(chunk)
      if (value !== '\n' && value !== '\r' && value !== '') {
        process.stdout.write(eraseLine + prompt)
      }
    }

    process.stdout.write(prompt)
    process.stdin.on('data', repaint)

    rl.once('line', (line) => {
      process.stdin.removeListener('data', repaint)
      rl.close()
      process.stdout.write('\n')
      resolve(line)
    })
    rl.once('error', (error) => {
      process.stdin.removeListener('data', repaint)
      reject(error)
    })
  })
}

function usage() {
  console.log(`
  Kadr server management

    node src/cli.js user add <username>       create an account
    node src/cli.js user passwd <username>    change a password
    node src/cli.js user list                 who has an account
    node src/cli.js device list               which phones are signed in
    node src/cli.js device revoke <id>        cut a phone off
`)
}
