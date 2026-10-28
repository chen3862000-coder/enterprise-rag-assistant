import { spawn } from 'node:child_process'

const composeFiles = (process.env.E2E_COMPOSE_FILES ?? '../compose.yaml').split(',')
const managedCompose = process.env.E2E_COMPOSE_MANAGED === 'true'
const composeProject = process.env.E2E_COMPOSE_PROJECT

function composeArgs(args) {
  return [...(composeProject ? ['-p', composeProject] : []),
    ...composeFiles.flatMap(file => ['-f', file]), ...args]
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', (code, signal) => resolve(code ?? (signal ? 1 : 0)))
  })
}

async function main() {
  if (!managedCompose) {
    const startup = await run('docker', ['compose', ...composeArgs(['up', '--build', '--detach', '--wait',
      'postgres', 'ollama', 'backend', 'frontend'])])
    if (startup !== 0) {
      await run('docker', ['compose', ...composeArgs(['down'])])
      process.exitCode = startup
      return
    }
  }

  try {
    process.exitCode = await run('npx', ['playwright', 'test'])
  } finally {
    if (!managedCompose) {
      await run('docker', ['compose', ...composeArgs(['down'])])
    }
  }
}

await main()
