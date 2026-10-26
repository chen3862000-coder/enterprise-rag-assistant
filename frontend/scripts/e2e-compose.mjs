import { spawn } from 'node:child_process'

const composeFile = '../compose.yaml'
const managedCompose = process.env.E2E_COMPOSE_MANAGED === 'true'

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', (code, signal) => resolve(code ?? (signal ? 1 : 0)))
  })
}

async function main() {
  if (!managedCompose) {
    const startup = await run('docker', ['compose', '-f', composeFile, 'up', '--build', '--detach', '--wait',
      'postgres', 'ollama', 'backend', 'frontend'])
    if (startup !== 0) {
      await run('docker', ['compose', '-f', composeFile, 'down'])
      process.exitCode = startup
      return
    }
  }

  try {
    process.exitCode = await run('npx', ['playwright', 'test'])
  } finally {
    if (!managedCompose) {
      await run('docker', ['compose', '-f', composeFile, 'down'])
    }
  }
}

await main()
