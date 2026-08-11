local breakSocketHandle, debugXpCall
local bDebug = true --_DEBUG
if bDebug then -- Debug模式启动lua调试
  breakSocketHandle,debugXpCall = require("LuaDebugjit")("localhost",7003)
end

local Math = require "CMath"
local Engine = require "CEngine"
local Core = require "MICore"
local mijson = require "mijson"

local main = {}
local begintime, cumtime, fps, numtime

local function _TraverseScriptEnable(node, benable)
  local children = node:getChildren();
  for i=1, #children do
    local child = children[i];
    local comp = child:getComponent(Engine.ScriptComponent:RTTI());
    if comp then
      comp:setScriptInvokEnable(benable)
    end
    _TraverseScriptEnable(child, benable)
  end
end

local function _SetScriptEnable(benable)
  local scenes = Engine.SceneManager:getAllScenes()
  for k,v in pairs(scenes) do
    local sce = v
    local rootNode = sce:getRootNode();
    _TraverseScriptEnable(rootNode, benable);
  end
  return true
end

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
  local asstPath = Core.IFileSystem:pathAssembly("proj:assets/")
  Core.IFileSystem:setAsstPath(asstPath);

  AddScriptPath(asstPath)

  local sce = Engine.SceneManager:createScene("PreviewScene", false)
  local projectConfigFile = Core.IFileSystem:pathAssembly("proj:ProjectConfig.json")
  local projectConfig = mijson.LoadJsonFile(projectConfigFile)

  local defaultScenePath = Core.IFileSystem:pathAssembly("proj:" .. projectConfig.default_scene)
  LoadAsset(defaultScenePath, sce)

  -- 设置脚本执行
  _SetScriptEnable(true)

  LOGI("lua init");
  fps = 0;
  numtime = 0
end

local function Timespan()
  fps = fps + 1;
  numtime = numtime + Core.ITimeSystem:getDetTime();
  
  if numtime > 1 then
    LOGI("FPS "..fps / numtime);
    fps = 0;
    numtime = 0;
  end
  return def;
end

function WallpaperUpdate()
  Timespan();
end

function ScriptInsertInstance(scr, path)
  if bDebug then
    breakSocketHandle();
  end
  local instance = Core.LoadBehavior(path)
  if instance then
    return scr:insertInstance(path, instance)
  end
  return false
end

return main
