local M = {}

local name = 'heddle'
local marker = '.heddle.toml'

local options
local languages_by_root = {}

-- Neovim only runs the commands a server lists when it starts, but the proxy
-- learns its backends' commands later. Send it every command that Neovim
-- doesn't run itself.
local function forward_unknown_commands(client)
  setmetatable(client.commands, {
    __index = function(_, command)
      if not vim.lsp.commands[command] then
        return function(cmd, ctx)
          client:request('workspace/executeCommand', { command = command, arguments = cmd.arguments }, nil, ctx.bufnr)
        end
      end
    end,
  })
end

-- The server types that `cmd` names after `serve`, which are the only ones the
-- proxy will serve.
local function server_types()
  local after_serve = false
  local names = {}
  for i = 2, #options.cmd do
    local arg = options.cmd[i]
    if after_serve and not vim.startswith(arg, '-') then
      table.insert(names, arg)
    end
    after_serve = after_serve or arg == 'serve'
  end
  return names
end

local function read_languages(root)
  local cmd = vim.list_extend({ options.cmd[1], 'languages', root }, server_types())
  local ok, result = pcall(function()
    return vim.system(cmd, { text = true, timeout = 5000 }):wait()
  end)
  local problem
  if not ok then
    problem = result
  elseif result.code == 124 then
    problem = 'It timed out.'
  elseif result.code ~= 0 then
    problem = result.stderr
  end
  local decoded = not problem and vim.F.npcall(vim.json.decode, result.stdout)
  if type(decoded) ~= 'table' or type(decoded.languages) ~= 'table' then
    vim.notify(
      ("heddle: couldn't read which languages %s covers, so other servers may start there too.\n%s"):format(
        vim.fs.joinpath(root, marker),
        problem or result.stdout
      ),
      vim.log.levels.WARN
    )
    return {}
  end
  local languages = {}
  for _, language in ipairs(decoded.languages) do
    languages[language] = true
  end
  return languages
end

local function covered(bufnr)
  local root = vim.fs.root(bufnr, marker)
  if not root then
    return false
  end
  languages_by_root[root] = languages_by_root[root] or read_languages(root)
  return languages_by_root[root][vim.bo[bufnr].filetype] == true
end

local function leave_to_proxy(config, bufnr)
  return config.name ~= name and not vim.list_contains(options.keep, config.name) and covered(bufnr)
end

-- Neovim has no hook that runs before a client starts, so this stands in for
-- `vim.lsp.start`, which `vim.lsp.enable` calls for every config.
local start
local function start_unless_covered(config, opts)
  opts = opts or {}
  if opts.attach ~= false then
    local bufnr = opts.bufnr or 0
    if bufnr == 0 then
      bufnr = vim.api.nvim_get_current_buf()
    end
    if leave_to_proxy(config, bufnr) then
      return nil
    end
  end
  return start(config, opts)
end

--- Configures and enables the `heddle` client, and stops other clients
--- from starting for files whose filetype the repo's config covers.
---
--- @param opts? { cmd?: string[], keep?: string[] }
---   - cmd: how to run the proxy (default `{ 'heddle' }`).
---   - keep: names of other clients to start as usual.
function M.setup(opts)
  options = vim.tbl_extend('force', { cmd = { 'heddle' }, keep = {} }, opts or {})
  languages_by_root = {}
  vim.lsp.config(name, {
    cmd = options.cmd,
    root_markers = { marker },
    workspace_required = true,
    on_init = forward_unknown_commands,
  })
  if vim.lsp.start ~= start_unless_covered then
    start = vim.lsp.start
    vim.lsp.start = start_unless_covered
  end
  vim.lsp.enable(name)
end

return M
