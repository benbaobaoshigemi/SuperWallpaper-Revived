local Core = require "MICore"
local Engine = require "CEngine"

local camBehavior = Core.Behavior:extend("camBehavior");

function camBehavior:ctor()
  camBehavior.super.ctor(self);
  --self.anim = self.Root::getComponent("AnimationComponent");
  
end

function camBehavior:_OnAwake()
  Engine.setTargetFrameRate(120);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_AOD);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_AOD1);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_AOD2);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_LOCK);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_HOME);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME1);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME2);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME3);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME4);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME5);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME6);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME7);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME8);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_TIME9);
end

function camBehavior:_OnStart()




end

function camBehavior:_OnUpdate()
end

function camBehavior:aod()
	LOGI("Engine.MessageType.SA_WALLPAPER_AOD...")
end

function camBehavior:Message(mt)
  if mt == Engine.MessageType.SA_WALLPAPER_AOD then
  elseif mt == Engine.MessageType.SA_WALLPAPER_AOD1 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_AOD2 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_LOCK then
  elseif mt == Engine.MessageType.SA_WALLPAPER_HOME then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME1 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME2 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME3 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME4 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME5 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME6 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME7 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME8 then
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME9 then
  end
end

return camBehavior;
