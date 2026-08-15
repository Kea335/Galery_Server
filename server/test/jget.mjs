// Tiny JSON field reader so the curl suite needs no jq.
// usage: cat body.json | node jget.mjs data.uploadId
let raw = ''
process.stdin.setEncoding('utf8')
for await (const chunk of process.stdin) raw += chunk

let value
try {
  value = JSON.parse(raw)
} catch {
  console.log('')
  process.exit(0)
}

for (const key of process.argv[2].split('.')) {
  value = value == null ? undefined : value[key]
}

if (Array.isArray(value)) console.log(value.join(','))
else console.log(value === undefined || value === null ? '' : String(value))
