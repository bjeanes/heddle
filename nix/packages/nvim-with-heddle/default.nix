# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

{
  lib,
  writeText,
  writeShellApplication,
  neovim-unwrapped,
  vim-plugin,
}:

let
  init = writeText "init.lua" ''
    vim.opt.runtimepath:prepend('${vim-plugin}')
    require('heddle').setup()
    vim.diagnostic.config({ virtual_text = true })
    if vim.fn.argc() == 1 and vim.fn.isdirectory(vim.fn.argv(0)) == 1 then
      vim.cmd.cd(vim.fn.argv(0))
    end
  '';
in
writeShellApplication {
  name = "nvim-with-heddle";
  text = ''
    export NVIM_APPNAME=nvim-with-heddle
    exec ${lib.getExe neovim-unwrapped} -u ${init} "$@"
  '';
}
