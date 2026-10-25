-- Checks that Neovim's own LSP client works with monorepo-lsp, configured as
-- in README.md, against examples/monorepo. Run it with the example's Compose
-- project up:
--
--   nvim --clean --headless -l dev/nvim-smoke.lua out/monorepo-lsp
--
-- It exits non-zero if any check fails. It changes buffers but never saves
-- them.

local proxy = arg[1] and vim.fn.fnamemodify(arg[1], ':p')
if not proxy or vim.fn.executable(proxy) ~= 1 then
  io.stderr:write('usage: nvim --clean --headless -l dev/nvim-smoke.lua PATH-TO-MONOREPO-LSP\n')
  os.exit(2)
end
local script = vim.fn.fnamemodify(debug.getinfo(1, 'S').source:sub(2), ':p')
local example = vim.fs.joinpath(vim.fs.dirname(vim.fs.dirname(script)), 'examples/monorepo')

vim.env.PATH = vim.fs.dirname(proxy) .. ':' .. vim.env.PATH
vim.cmd('filetype on')

-- File watching isn't under test here. Inside Claude Code's sandbox, its
-- watcher fails with EMFILE. The error then aborts whatever request is waiting.
vim.lsp.config('*', { capabilities = { workspace = { didChangeWatchedFiles = { dynamicRegistration = false } } } })

-- Stand-ins for nvim-lspconfig's configs, which the setup below has to keep
-- out of the way.
vim.lsp.config('ruby_lsp', { cmd = { 'ruby-lsp' }, filetypes = { 'ruby' }, root_markers = { 'Gemfile', '.git' } })
vim.lsp.config('rubocop', { cmd = { 'rubocop', '--lsp' }, filetypes = { 'ruby' }, root_markers = { 'Gemfile', '.git' } })
vim.lsp.enable({ 'ruby_lsp', 'rubocop' })

-- BEGIN README setup
vim.lsp.config('monorepo_lsp', {
  cmd = { 'monorepo-lsp' },
  root_markers = { '.monorepo-lsp.toml' },
  workspace_required = true,
  on_init = function(client)
    -- Neovim only runs the commands a server lists when it starts, but the
    -- proxy learns its backends' commands later. Send it every command that
    -- Neovim doesn't run itself.
    setmetatable(client.commands, {
      __index = function(_, command)
        if not vim.lsp.commands[command] then
          return function(cmd, ctx)
            client:request('workspace/executeCommand', { command = command, arguments = cmd.arguments }, nil, ctx.bufnr)
          end
        end
      end,
    })
  end,
})
vim.lsp.enable('monorepo_lsp')

-- In a repo with a .monorepo-lsp.toml, leave these to the proxy.
for _, name in ipairs({ 'ruby_lsp', 'rubocop' }) do
  vim.lsp.config(name, {
    root_dir = function(bufnr, on_dir)
      if not vim.fs.root(bufnr, '.monorepo-lsp.toml') then
        on_dir(vim.fs.root(bufnr, { 'Gemfile', '.git' }))
      end
    end,
  })
end
-- END README setup

local failures = 0

local function check(ok, what, detail)
  print(('%s %s%s'):format(ok and 'ok  ' or 'FAIL', what, detail and (': ' .. detail) or ''))
  if not ok then
    failures = failures + 1
  end
  return ok
end

local function short(s)
  s = tostring(s):gsub('%s+', ' ')
  return #s > 120 and (s:sub(1, 120) .. '…') or s
end

-- Asks until there's an answer, since ruby-lsp answers with nothing until it
-- has indexed the bundle.
local function request(client, method, bufnr, position)
  local params = { textDocument = { uri = vim.uri_from_bufnr(bufnr) }, position = position }
  local result, err
  vim.wait(60000, function()
    local response
    response, err = client:request_sync(method, params, 20000, bufnr)
    result = response and response.result
    err = err or (response and response.err and response.err.message)
    return err ~= nil or (result ~= nil and not vim.tbl_isempty(result))
  end, 500)
  return result, err
end

local function sources(bufnr)
  local found = {}
  for _, d in ipairs(vim.diagnostic.get(bufnr)) do
    found[d.source or '?'] = (found[d.source or '?'] or 0) + 1
  end
  return found
end

local function check_file(case)
  print(('\n%s'):format(case.file))
  vim.cmd.edit(vim.fs.joinpath(example, case.file))
  local bufnr = vim.api.nvim_get_current_buf()

  local client
  vim.wait(30000, function()
    client = vim.lsp.get_clients({ bufnr = bufnr, name = 'monorepo_lsp' })[1]
    return client ~= nil and client.initialized
  end, 100)
  if not check(client ~= nil, 'monorepo_lsp attached') then
    return
  end
  local others = vim.tbl_map(function(c)
    return c.name
  end, vim.lsp.get_clients({ bufnr = bufnr }))
  check(#others == 1, 'no other client attached', table.concat(others, ', '))

  check(
    vim.wait(60000, function()
      return client:supports_method('textDocument/hover', bufnr)
        and client:supports_method('textDocument/definition', bufnr)
    end, 100),
    'hover and definition registered after startup',
    ('static hoverProvider=%s'):format(vim.inspect(client.server_capabilities.hoverProvider))
  )

  local hover, err = request(client, 'textDocument/hover', bufnr, case.hover)
  local text = hover and hover.contents and (hover.contents.value or vim.inspect(hover.contents))
  check(text ~= nil and text:find(case.hover_text, 1, true) ~= nil, 'hover', short(text or err))

  local definition
  definition, err = request(client, 'textDocument/definition', bufnr, case.definition)
  local location = definition and (definition[1] or definition)
  local uri = location and (location.targetUri or location.uri)
  local path = uri and vim.uri_to_fname(uri)
  check(
    path ~= nil and vim.endswith(path, case.definition_file) and not vim.startswith(path, example),
    'definition in a fetched file',
    path or err
  )
  if path then
    local lines = vim.fn.filereadable(path) == 1 and vim.fn.readfile(path) or {}
    check(#lines > 0, 'fetched file readable on the host', ('%d lines'):format(#lines))
  end

  local found = {}
  local done = vim.wait(30000, function()
    found = sources(bufnr)
    for _, source in ipairs(case.sources) do
      if not found[source] then
        return false
      end
    end
    return true
  end, 200)
  check(done, 'diagnostics from ' .. table.concat(case.sources, ' and '), vim.inspect(found, { newline = ' ', indent = '' }))
  for _, d in ipairs(vim.diagnostic.get(bufnr)) do
    print(('     %d:%d %s %s %s'):format(d.lnum, d.col, d.source or '?', d.code or '', short(d.message)))
  end

  return client, bufnr
end

check_file({
  file = 'services/api/lib/greeting.rb',
  hover = { line = 12, character = 4 },
  hover_text = 'Rainbow',
  definition = { line = 12, character = 12 },
  definition_file = 'gems/rainbow-3.1.1/lib/rainbow/global.rb',
  sources = { 'RuboCop' },
})

local client, bufnr = check_file({
  file = 'services/billing/lib/invoice.rb',
  hover = { line = 8, character = 9 },
  hover_text = 'Dry::Inflector',
  definition = { line = 8, character = 23 },
  definition_file = 'gems/dry-inflector-1.3.1/lib/dry/inflector.rb',
  sources = { 'RuboCop', 'Prism' },
})

if client then
  print('\ncommands')
  local provider = client.server_capabilities.executeCommandProvider
  print(('     executeCommandProvider at startup: %s'):format(vim.inspect(provider, { newline = ' ', indent = '' })))
  local before = vim.api.nvim_buf_get_lines(bufnr, 0, 1, false)[1]
  client:exec_cmd({
    title = 'Autocorrect',
    command = 'rubocop:rubocop.formatAutocorrects',
    arguments = { { uri = vim.uri_from_bufnr(bufnr) } },
  }, { bufnr = bufnr })
  local first
  vim.wait(20000, function()
    first = vim.api.nvim_buf_get_lines(bufnr, 0, 1, false)[1]
    return first ~= before
  end, 100)
  check(first == "require 'dry/inflector'", "RuboCop's autocorrect command edited the buffer", first)
  check(
    vim.wait(20000, function()
      return vim.iter(pairs(client.requests)):all(function(_, r)
        return r.method ~= 'workspace/executeCommand'
      end)
    end, 100),
    "RuboCop's autocorrect command answered"
  )

  local ran_locally = false
  vim.lsp.commands['smoke.local'] = function()
    ran_locally = true
  end
  client:exec_cmd({ title = 'Local', command = 'smoke.local' }, { bufnr = bufnr })
  check(ran_locally, "Neovim's own commands still run in Neovim")
end

for _, c in ipairs(vim.lsp.get_clients()) do
  c:stop()
end
vim.wait(20000, function()
  return #vim.lsp.get_clients() == 0
end, 100)

print(('\n%s'):format(failures == 0 and 'all checks passed' or (failures .. ' check(s) failed')))
os.exit(failures == 0 and 0 or 1)
