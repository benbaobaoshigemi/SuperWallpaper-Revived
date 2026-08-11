local Math = require "CMath"
local Engine = require "CEngine"
local Core = require "MICore.init"

local main = {}

function AddScriptPath(path)
  local scriptpath = Core.IFileSystem:pathAssembly(path);
  local temp = scriptpath .."?.lua;";
  temp = temp .. scriptpath .."?/init.lua;"
  temp = temp .. scriptpath .."?/?.lua;"
  package.path = temp .. package.path;
end

function WallpaperInit(path)
  local projectpath = path
  Core.IFileSystem:setProjPath(projectpath);
  local assetPath = Core.IFileSystem:pathAssembly(projectpath .. "assets/")
  Core.IFileSystem:setAsstPath(assetPath);
  AddScriptPath(assetPath)

  LOGI("lua init");
end

function WallpaperUpdate()
end

function ScriptInsertInstance(scr, path)
  local instance = Core.LoadBehavior(path)
  if instance then
    return scr:insertInstance(path, instance)
  end
  return false
end

return main