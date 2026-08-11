local Core
if _PLATFORM_ANDROID then
  Core = require "MICore.init"
else
  Core = require "MICore"
end
local Engine = require "CEngine"
local Math = require "CMath"

local CamBehavior = Core.Behavior:extend("CamBehavior")

local State = {
  AOD = 0,
  LOCK = 1,
  HOME = 2,
  FORCEAOD = 3,
  FORCELOCK = 4,
  FORCEHOME = 5
}

local AOColor = {
  Math.float4(0.275, 0.2, 0.202, 0.204),
  Math.float4(0.275, 0.2, 0.204, 0.204),
  Math.float4(0.275, 0.154, 0.039, 0.384),
  Math.float4(0.040, 0.204, 0.274, 0.204),
  Math.float4(0.275, 0.039, 0.043, 0.204),
  Math.float4(0.275, 0.039, 0.043, 0.204),
  Math.float4(0.275, 0.039, 0.043, 0.204),
  Math.float4(0.292, 0.214, 0.285, 0.204),
  Math.float4(0.517, 0.639, 0.708, 0.824)
}

local AOIntensity = {
  Math.float4(1.60, 1.54, 0.20, 0.0),
  Math.float4(1.60, 1.54, 0.20, 0.0),
  Math.float4(1.11, 1.54, 0.20, 1.0),
  Math.float4(1.11, 1.54, 0.20, 0.0),
  Math.float4(1.11, 1.54, 0.20, 0.0),
  Math.float4(1.11, 1.54, 0.20, 0.0),
  Math.float4(1.60, 1.54, 0.20, 0.0),
  Math.float4(1.80, 1.54, 0.20, 0.0),
  Math.float4(1.00, 1.00, 0.20, 0.0)
}

-- 插值动画速度
local lerpSpeed = 2.5

-- aod/lock/home 三种状态参数
local aodMainCameraPos = Math.float3(79.3, -36.95, 180.0)
local aodMainCameraRot = Math.float3(-7.9, 23.115, 0.0)
local aodMainCameraDarkState = 1.0
local aodMainCameraRotValue = 0.0
local aodMainCameraStateValue = 0.0
local aodMainCameraTargetHighValue = 0.0
local aodMainCameraLookAt = Math.float3(2.18, -9.7, 0.671)

local lockMainCameraPos = Math.float3(-3.02, -9.44, 64.5)
local lockMainCameraRot = Math.float3(-6.023, -3.6, 0)
local lockMainCameraDarkState = 0.0
local lockMainCameraRotValue = 0.0
local lockMainCameraStateValue = 0.5
local lockMainCameraTargetHighValue = 1.0
local lockMainCameraLookAt = Math.float3(1.076, -2.55, 0.67)

local homeMainCameraPos = Math.float3(-31.6, -10.6, 66.8)
local homeMainCameraRot = Math.float3(4.98, -24.86, 0)
local homeMainCameraDarkState = 0.0
local homeMainCameraRotValue = 1.0
local homeMainCameraStateValue = 1.0
local homeMainCameraTargetHighValue = 1.0
local homeMainCameraLookAt = Math.float3(-0.34, -4.12, 0.67)

-- 记录当前时刻参数，插值使用，初始状态跟aod一致
local curMainCameraPos = aodMainCameraPos
local curMainCameraRot = aodMainCameraRot
local curMainCameraDarkState = aodMainCameraDarkState
local curMainCameraRotValue = aodMainCameraRotValue
local curMainCameraStateValue = aodMainCameraStateValue
local curMainCameraTargetHighValue = aodMainCameraTargetHighValue
local curMainCameraLookAt = aodMainCameraLookAt

-- 记录动画已执行的时间，动画后期降帧率使用
local curTotalTransTime = 0
local isReduceFrameRate = false

-- 记录目标参数，插值使用
local tgtMainCameraPos = aodMainCameraPos
local tgtMainCameraRot = aodMainCameraRot
local tgtMainCameraDarkState = aodMainCameraDarkState
local tgtMainCameraRotValue = aodMainCameraRotValue
local tgtMainCameraStateValue = aodMainCameraStateValue
local tgtMainCameraTargetHighValue = aodMainCameraTargetHighValue
local tgtMainCameraLookAt = aodMainCameraLookAt

-- 标志位,辅助帧率策略,并不代表桌面滑动实际状态
local expectHomeChanging = false

-- 标志位，辅助aod下模型更新移动
local isAodoffset = false

-- 标志位，屏幕是否处于横屏, 0是竖屏，1是横屏
local isLandscape = 0

local function _GetMetaName(path)
  if path == nil then
    LOGE("texture path is nil");
    return nil;
  end
  local metaPath = Core.IFileSystem:pathAssembly(path) .. ".meta";
  return metaPath;
end 

local function _ConverToRelativePath(absolutepath, targetPath)
  local relativepath = absolutepath;
  local replacestr = targetPath or "proj:";
  local projectpath = Core.IFileSystem:pathAssembly(replacestr);
  local _, sEnd = string.find(absolutepath,projectpath,1,true);
  if sEnd ~= nil then
    relativepath = string.sub(absolutepath,sEnd + 1);
    relativepath = replacestr .. relativepath;
  end
  return relativepath;
end

local function _GetDefaultTex()
  local defaultTex = Engine.TextureEntity();
  local defaultpath = _PLATFORM_ANDROID and "comm:/texture/white.ktx" or "comm:/texture/white.png"
  defaultTex:pushMetadata(Engine.TextureFileMetadata(
    Core.RHIDefine.TEXTURE_2D,
    Core.RHIDefine.PF_AUTO,1,false,
    Core.RHIDefine.TW_CLAMP_TO_EDGE,
    Core.RHIDefine.TW_CLAMP_TO_EDGE,
    Core.RHIDefine.TW_CLAMP_TO_EDGE,
    Core.RHIDefine.TF_LINEAR,
    Core.RHIDefine.TF_LINEAR_MIPMAP_LINEAR,
    defaultpath));
  defaultTex:createResource();
  return defaultTex
end

local function _GetTex(texPath)
  --"proj:assets/texture/center2.png.meta"
  local tex = Engine.TextureEntity();
  tex:pushMetadata(Engine.TextureDescribeFileMetadata(texPath));
  tex:createResource();
  return tex
end

local function _GetComponentsInChildren(go, rtti)
  local children = go:getChildren()
  local result = {}
  for i=1,#children do
    if not children[i]:isLayer(Engine.LayerMask.MC_MASK_EDITOR_SCENE_LAYER) and not children[i]:isLayer(Engine.LayerMask.MC_MASK_EDITOR_UI_LAYER) then
      local comp = children[i]:getComponent(rtti)
      if comp then
        result[#result+1] = comp
      end
    end
    local childResult = _GetComponentsInChildren(children[i], rtti)
    for j=1,#childResult do
      result[#result+1] = childResult[j]
    end
  end
  return result
end

function CamBehavior:ctor()
  CamBehavior.super.ctor(self)
  self.rateValue = 0
  self.autoFrameRate = true
  self.startRate = 1
  self.saveRate = 0
  self.maxRot = Math.float2(23,3)
  
  self.transTime = 0.4 / 1.2
  self.curRot = 0
  self.tgtRot = 0
  self.rotValue = 0
  self.speed = 1
  self.deskSlideDeltaTime = 0.0666

  self.forceAni = false
  self.maxYaw = -10
  self.mountainPos = Math.float3(0.0, 0.0, 0.0)
  self.sgnRot = Math.float3(0.0, 9.21, 0.0)
  self.lookAt = Math.float3(1.08, -2.55, 0.67)
  self.darkState = 0.0
  self.stateValue = 0.0
  self.sunriseTime = 21600
  self.sunSetTime = 64800
  self.goStop = 5
  self.autoTime = true
  self.screenHeight = 0


  self.mountain = nil
  self.mountainPos = Math.float3(0.0)
  self.mountainMtl = {}
  self.skyMtl = {}

  self.curState = State.AOD
  self.prevState = State.AOD

  self.forceRemap = 0
  self.timeRemap = {1, 1, 2, 4, 6, 6, 7, 8, 9}
  self.fadeTimer = 0.0
  self.fade = 5.0
  self.fadeTrans = 2.0
  self.fadeLerp = 0.0

  self.Clouds = {}
  self.defaultTex = nil

  self.heightSpeed = 0
  self.targetHeight = 0
  self.curHeight = 0
  self.targetHeightSpeed = 3
  self.targetHeightValue = 0
  self.resScale = 0.8
  

  self.curTimeSlice = 0

  self.lookAt = Math.float3(0, 0, 0)
  self.upVector = Math.float3(0.0, 1.0, 0.0)

end

function CamBehavior:_OnAwake()
  self.defaultTex = _GetDefaultTex()
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

  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_FORCE_AOD);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_FORCE_LOCK);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_FORCE_HOME);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_CLOUD_ON_OFF);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_SUNRISE_30S);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_AOD_OFFSET);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_HOME_OFFSET);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_SET_REFRESH);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_RESOLUTION);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_IS_LANDSCAPE);
  self.Script:registerMessage(Engine.MessageType.SA_WALLPAPER_MESSAGE_NOT_MATCH);

  self.ani = self.Root:getComponent(Engine.AnimationComponent:RTTI())

  local root = self.Root:getRoot();
  self.mountain = root:getChild("sgn")
  local center = self.mountain:getChild("center.001")
  local sky = self.mountain:getChild("Sky")

  self.sgnRot = self.mountain:getComponent(Engine.TransformComponent:RTTI()):getLocalEularAngle()
  self.mountainPos = self.mountain:getComponent(Engine.TransformComponent:RTTI()):getLocalPosition()

  local mountainMaterialCount = center:getComponent(Engine.RenderComponent:RTTI()):getMaterialCount()
  local skyMaterialCount = sky:getComponent(Engine.RenderComponent:RTTI()):getMaterialCount()

  for i=1, mountainMaterialCount do
    local curMaterialEntity = center:getComponent(Engine.RenderComponent:RTTI()):getMaterialEntity(i-1)
    table.insert(self.mountainMtl, curMaterialEntity)
  end

  for i=1, skyMaterialCount do
    local curMaterialEntity = sky:getComponent(Engine.RenderComponent:RTTI()):getMaterialEntity(i-1)
    table.insert(self.skyMtl, curMaterialEntity)
  end

  local root = self.Root:getRoot();
  local sgn = root:getChild("sgnSyn")
  self.Clouds[1] = sgn:getChild("cloud1");
  self.Clouds[2] = sgn:getChild("cloud1");
  self.Clouds[3] = sgn:getChild("cloud2");
  self.Clouds[4] = sgn:getChild("cloud4");
  self.Clouds[5] = sgn:getChild("cloud6");
  self.Clouds[6] = sgn:getChild("cloud6");
  self.Clouds[7] = sgn:getChild("cloud7");
  self.Clouds[8] = sgn:getChild("cloud8");
  self.Clouds[9] = sgn:getChild("cloud9");
  self.homeplayps = {sgn:getChild("cloud1"):getChild("ray1"):getComponent(Engine.ParticleComponent:RTTI()), sgn:getChild("cloud2"):getChild("ray1"):getComponent(Engine.ParticleComponent:RTTI())}
end

function CamBehavior:_OnStart()
  self:SetRefresh(120)
  if self.autoFrameRate and self.saveRate > 60 then
    Engine.setRenderFrameInterval(self.startRate)
  end
  
  if _EDITOR then
    self.Sce:getRenderCamera():setBlendMode(Core.RHIDefine.VBM_TRANSLUCENT)
  end
end



function CamBehavior:GetTime(offset)
  local timestamp = os.time()
  local date = os.date("*t", timestamp)
  local totsec = date.hour * 3600 + date.min * 60 + date.sec + offset
  totsec = totsec % 86400
  return totsec
end

function CamBehavior:GetTimeSlice()
  local totsec = self:GetTime(0)

  if totsec >= (self.sunriseTime - 3600) and totsec < self.sunriseTime then 
    return 1
  elseif totsec >= self.sunriseTime and totsec < (self.sunriseTime + 7200) then 
    return 2
  elseif totsec >= (self.sunriseTime + 7200) and totsec < (self.sunriseTime + self.sunSetTime) / 2 then
    return 3
  elseif totsec >= (self.sunriseTime + self.sunSetTime) / 2 and totsec < (self.sunSetTime - 3600) then 
    return 4
  elseif totsec >= (self.sunSetTime - 3600) and totsec < self.sunSetTime then 
    return 5
  elseif totsec >= self.sunSetTime and totsec < (self.sunSetTime + 2400) then 
    return 6
  elseif totsec >= (self.sunSetTime + 2400) and totsec < (self.sunSetTime + 4800) then 
    return 7
  elseif totsec >= (self.sunSetTime + 4800) and totsec < (self.sunSetTime +7200) then 
    return 8
  else
    return 9
  end
end

function CamBehavior:ForceTime(ti)
  self.autoTime = false
  if self.fadeTimer == 0.0 then
    self:TimeChange(ti)
  end

end

function CamBehavior:UpdateTime()
  local ti = self.curTimeSlice
  if self.autoTime then
    ti = self:GetTimeSlice()
  end

  self:TimeChange(ti)

  if self.fadeTimer > 0 and self.fadeTimer < self.fade then
    self.fadeTimer = self.fadeTimer + self.deskSlideDeltaTime
    if self.fadeTimer >= self.fade then
      self.fadeTimer = 0.0
      
      if "PreviewScene" == self.Sce:getName() then

        local param = self.mountainMtl[1]:getParameter("mainTex2")
        local tex = Engine.ToConvertParamTex(param):GetTex()
        self.mountainMtl[1]:setParameter("mainTex", tex)
        self.mountainMtl[1]:setParameter("mainTex2", self.defaultTex)

        param = self.mountainMtl[2]:getParameter("mainTex2")
        tex = Engine.ToConvertParamTex(param):GetTex()
        self.mountainMtl[2]:setParameter("mainTex", tex)
        self.mountainMtl[2]:setParameter("mainTex2", self.defaultTex)

        param = self.skyMtl[1]:getParameter("mainTex2")
        tex = Engine.ToConvertParamTex(param):GetTex()
        self.skyMtl[1]:setParameter("mainTex", tex)
        self.skyMtl[1]:setParameter("mainTex2", self.defaultTex)

      end

      self.mountainMtl[1]:setParameter("colorContourTo", AOColor[ti])
      self.mountainMtl[1]:setParameter("colorContour", AOColor[ti])
      self.mountainMtl[1]:setParameter("intensityTo", AOIntensity[ti])
      self.mountainMtl[1]:setParameter("intensity", AOIntensity[ti])

      self.mountainMtl[2]:setParameter("colorContourTo", AOColor[ti])
      self.mountainMtl[2]:setParameter("colorContour", AOColor[ti])
      self.mountainMtl[2]:setParameter("intensityTo", AOIntensity[ti])
      self.mountainMtl[2]:setParameter("intensity", AOIntensity[ti])

    end

    local fadeIn = math.clamp((self.fadeTimer - (self.fade - self.fadeTrans)) / self.fadeTrans, 0.0, 1.0)
    self.mountainMtl[1]:setParameter("fadeIn", fadeIn)
    self.mountainMtl[2]:setParameter("fadeIn", fadeIn)
    self.skyMtl[1]:setParameter("fadeIn", fadeIn)

  end
end

function CamBehavior:CloudOnOff()
  if self.curTimeSlice > 0 and self.curTimeSlice <= #self.Clouds and self.Clouds[self.curTimeSlice] ~= nil then
    local ps = _GetComponentsInChildren(self.Clouds[self.curTimeSlice], Engine.ParticleComponent:RTTI())
      for i=1, #ps do
        if ps[i]:isPlaying() then
          ps[i]:stop()
          --ps[i]:clear()
        else
          ps[i]:play()
        end
      end
  end
end

-- 壁纸上层传来帧率信息
function CamBehavior:SetRefresh(rate)
  self.saveRate = rate
  if rate > 60 and self.autoFrameRate then
    Engine.setRenderFrameInterval(self.startRate)
  else
    Engine.setRenderFrameInterval(1)
  end
  Engine.setTargetFrameRate(rate)
end


function CamBehavior:TimeChange(ti)

  local newTi = ti
  local tti = ti

  if self.forceRemap >= 1 then
    tti = self.timeRemap[self.forceRemap]
    newTi = self.forceRemap
  else
    tti = self.timeRemap[ti]
  end

  if newTi ~= self.curTimeSlice then
    if self.curTimeSlice > 0 and self.curTimeSlice <= #self.Clouds and self.Clouds[self.curTimeSlice] then
      local ps = _GetComponentsInChildren(self.Clouds[self.curTimeSlice], Engine.ParticleComponent:RTTI())
      for i=1,#ps do
        ps[i]:stop()
        --ps[i]:clear()
      end
    end

    self.curTimeSlice = newTi

    if newTi < #self.Clouds and self.Clouds[newTi] then
      local ps = _GetComponentsInChildren(self.Clouds[newTi], Engine.ParticleComponent:RTTI())
      for i=1,#ps do
        ps[i]:play()
      end
    end

    --Try find the texture file.
    local texPath = "proj:assets/texture/center" .. tti .. ".png.meta"
    local tex = _GetTex(texPath)
    if tex then
      self.mountainMtl[1]:setParameter("mainTex2", tex)
      self.mountainMtl[1]:setParameter("colorContourTo", AOColor[newTi])
      self.mountainMtl[1]:setParameter("intensityTo", AOIntensity[newTi])
    end

    texPath = "proj:assets/texture/core" .. tti .. ".png.meta"
    tex = _GetTex(texPath)
    if tex then
      self.mountainMtl[2]:setParameter("mainTex2", tex)
      self.mountainMtl[2]:setParameter("colorContourTo", AOColor[newTi])
      self.mountainMtl[2]:setParameter("intensityTo", AOIntensity[newTi])
    end

    texPath = "proj:assets/texture/sky" .. tti .. ".png.meta"
    tex = _GetTex(texPath)
    if tex then
      self.skyMtl[1]:setParameter("mainTex2", tex)
    end

    self.fadeTimer = self.deskSlideDeltaTime 

  end

  return newTi
end


function CamBehavior:Lerp(a, b, r)
  return a * (1 - r) + b * r
end


function CamBehavior:_OnUpdate()
  local dt = Core.ITimeSystem:getDetTime()
  -- force动画和aod offset更新走下面逻辑，不走插值
  if self.forceAni or (isAodoffset and self.curState == State.AOD) then
    curMainCameraPos = tgtMainCameraPos
    curMainCameraRotValue = tgtMainCameraRotValue
    curMainCameraTargetHighValue = tgtMainCameraTargetHighValue
    curMainCameraLookAt = tgtMainCameraLookAt
    curMainCameraStateValue = tgtMainCameraStateValue
    curMainCameraDarkState = tgtMainCameraDarkState
    curTotalTransTime = 0
  else
    -- 插值计算更新参数
    if dt > 1 then
      LOGI("deltaTime calculation value is abnormal, Reset it: " .. dt)
      dt = self.deskSlideDeltaTime
    end

    if isLandscape == 1 and curMainCameraDarkState < 1.0 then
       curMainCameraDarkState = 1.0
    else
       curMainCameraDarkState = curMainCameraDarkState + (tgtMainCameraDarkState - curMainCameraDarkState) * dt * lerpSpeed
    end
    curMainCameraPos = curMainCameraPos + (tgtMainCameraPos - curMainCameraPos) * dt * lerpSpeed
    curMainCameraRotValue = curMainCameraRotValue + (tgtMainCameraRotValue - curMainCameraRotValue) * dt * lerpSpeed
    curMainCameraTargetHighValue = curMainCameraTargetHighValue + (tgtMainCameraTargetHighValue - curMainCameraTargetHighValue) * dt * lerpSpeed
    curMainCameraStateValue = curMainCameraStateValue + (tgtMainCameraStateValue - curMainCameraStateValue) * dt * lerpSpeed
    curMainCameraLookAt = curMainCameraLookAt + (tgtMainCameraLookAt - curMainCameraLookAt) * dt * lerpSpeed
    curTotalTransTime = curTotalTransTime + dt

    self:SetStateValue(curMainCameraStateValue)
    self:SetRotValue(curMainCameraRotValue)
    self:SetTargetHeightValue(curMainCameraTargetHighValue)
    self:SetDarkState(curMainCameraDarkState)
    self:SetLookAtX(curMainCameraLookAt.x)
    self:SetLookAtY(curMainCameraLookAt.y)
    self:SetLookAtZ(curMainCameraLookAt.z)

  end

  -- 过渡时间大于1.2时，开始降低帧率
  if curTotalTransTime >= 1.2 then
    isReduceFrameRate = true
  else
    isReduceFrameRate = false
  end

  -- 更新MainCamera位置
  self.Transform:setLocalPosition(curMainCameraPos)

  -- 更新MainCamera欧拉角
  local tp = curMainCameraLookAt - curMainCameraPos;
  local quat = Math.FromForwardToQuat(tp)
  self.Transform:setLocalRotation(quat)

  -- MainCamera欧拉角矫正
  local ea = self.Transform:getLocalEularAngle() * 180 / math.pi
  if ea.y > 180 then
    ea.y = ea.y - 360
    self.Transform:setLocalEularAngle(ea * math.pi / 180)
  end

  local targetRot = self.tgtRot
  local rot = self.sgnRot

  -- 桌面滑动动画：计算滑动角度
  if targetRot ~= self.curRot then
    rot = rot * 180 / math.pi
    self.curRot = self.curRot + (targetRot - self.curRot) * self.speed * self.deskSlideDeltaTime
    rot.y = rot.y + self:Lerp(0, self.maxYaw, self.curRot)
    rot = rot * math.pi / 180
  end

  self.mountain:getComponent(Engine.TransformComponent:RTTI()):setLocalEularAngle(self:Lerp(self.sgnRot, rot, curMainCameraRotValue))

  -- darkState 控制背景颜色
  local darkState = math.clamp(curMainCameraDarkState * 2.0, 0.0, 1.0)
  self.mountainMtl[1]:setParameter("state", darkState)
  self.mountainMtl[2]:setParameter("state", darkState)
  self.skyMtl[1]:setParameter("state", darkState)
  for i=1, #self.Clouds do
    local ren = _GetComponentsInChildren(self.Clouds[i], Engine.RenderComponent:RTTI())
    for j=1,#ren do
      ren[j]:setParameter("_State", darkState)
    end
  end

  -- 0:error，1:playing, 2:done, 3:stop, 4:loop_continue
  -- 未使用动画脚本，getAnimationStatus不生效
  -- local isTransing = self.ani:getAnimationStatus()

  -- aod/lock/home状态切换帧率策略, 不限制帧率;动画后期降低至40帧
  if (self.curState == State.AOD or self.curState == State.LOCK or self.curState == State.HOME) and not expectHomeChanging and not isReduceFrameRate then
    self.goStop = 5;
    if self.autoFrameRate and self.saveRate > 60 and Engine.getRenderFrameInterval() ~= self.startRate then
      Engine.setRenderFrameInterval(self.startRate)
    end
  -- 桌面滑动时,动画跳帧策略,限制30帧
  elseif self:HomeChanging() and self.curState == State.HOME and expectHomeChanging then
    self.goStop = 5
    if self.autoFrameRate then
      local curInterval = Engine.getRenderFrameInterval()
      -- 如果刚解锁进入桌面，立刻滑动桌面，由于此时动画速度较快，限制至30fps动画不流畅，这里用curMainCameraRotValue>0.9的条件等待动画速度降低时再调整帧率
      if curMainCameraRotValue > 0.9 then
        if curInterval ~= 3 and self.saveRate == 90 then
          Engine.setRenderFrameInterval(3)
        elseif curInterval ~= 4 and self.saveRate == 120 then
          Engine.setRenderFrameInterval(4)
        elseif curInterval ~= 2 and self.saveRate == 60 then
          Engine.setRenderFrameInterval(2)
        end
      end
    end
  else
    -- aod->lock/lock->home/home->aod的动画后期降帧率至40帧
    self.goStop = self.goStop - 1
    if self.saveRate > 60 and self.goStop <= 0 then
      self.goStop = 0
      Engine.setRenderFrameInterval(3)
    elseif self.saveRate == 60 and self.goStop <= 0 then
      self.goStop = 0
      Engine.setRenderFrameInterval(2)
    end
  end

  -- aod offset 更新计算
  if self.targetHeight > self.curHeight then
    self.curHeight = self.curHeight + dt * math.abs(self.targetHeight - self.curHeight) * self.heightSpeed
    isAodoffset = false
    if self.curHeight > self.targetHeight then
      self.curHeight = self.targetHeight
    end
  elseif self.targetHeight < self.curHeight then
    self.curHeight = self.curHeight - dt * math.abs(self.targetHeight - self.curHeight) * self.heightSpeed
    isAodoffset = false
    if self.curHeight < self.targetHeight then
      self.curHeight = self.targetHeight
    end
  end

  if self.heightSpeed ~= self.targetHeightSpeed then
    self.heightSpeed = self.targetHeightSpeed
  end

  -- 设置模型的位置Pos
  local lookAtOffset= self.upVector * self:Lerp(self.curHeight, 0.0, curMainCameraTargetHighValue)
  self.mountain:getComponent(Engine.TransformComponent:RTTI()):setLocalPosition(self.mountainPos + lookAtOffset)

  self:UpdateTime()

end

function CamBehavior:AodOffsetTo(val)
  self.targetHeight = val
  self.heightSpeed = 10000
end

function CamBehavior:HomeChange(val)
  if self.curState == State.HOME then
    expectHomeChanging = true
  else
    expectHomeChanging = false
  end
  self.tgtRot = val
end

function CamBehavior:HomeChanging()
  if math.abs(self.tgtRot - self.curRot) > 0.01 then
    return true
  end
  expectHomeChanging = false
  return false
end





function CamBehavior:Message(mt, value)
  if mt == Engine.MessageType.SA_WALLPAPER_AOD then
    self:SwitchTo(0, 0)
  elseif mt == Engine.MessageType.SA_WALLPAPER_AOD1 then
    self:SwitchTo(0, 1)
  elseif mt == Engine.MessageType.SA_WALLPAPER_AOD2 then
    self:SwitchTo(0, 2)
  elseif mt == Engine.MessageType.SA_WALLPAPER_LOCK then
    isLandscape = 0
    self:SwitchTo(1)
  elseif mt == Engine.MessageType.SA_WALLPAPER_HOME then
    isLandscape = 0
    self:SwitchTo(2)
  elseif mt == Engine.MessageType.SA_WALLPAPER_FORCE_AOD then
    self:SwitchTo(0, 1, true);
  elseif mt == Engine.MessageType.SA_WALLPAPER_FORCE_LOCK then
    isLandscape = 0
    self:SwitchTo(1, 0, true);
  elseif mt == Engine.MessageType.SA_WALLPAPER_FORCE_HOME then
    isLandscape = 0
    self:SwitchTo(2, 0, true);
  elseif mt == Engine.MessageType.SA_WALLPAPER_CLOUD_ON_OFF then
    self:CloudOnOff()
  elseif mt == Engine.MessageType.SA_WALLPAPER_SUNRISE_30S then
    --查看固定时间的日照效果
    self.sunriseTime = 26640
    self.sunSetTime = 60600
  elseif mt == Engine.MessageType.SA_WALLPAPER_AOD_OFFSET then
    isAodoffset = true
    self:AodOffsetTo(value)
  elseif mt == Engine.MessageType.SA_WALLPAPER_RESOLUTION then
    self.screenHeight = value
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME1 then
    self:ForceTime(1)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME2 then
    self:ForceTime(2)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME3 then
    self:ForceTime(3)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME4 then
    self:ForceTime(4)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME5 then
    self:ForceTime(5)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME6 then
    self:ForceTime(6)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME7 then
    self:ForceTime(7)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME8 then
    self:ForceTime(8)
  elseif mt == Engine.MessageType.SA_WALLPAPER_TIME9 then
    self:ForceTime(9)
  elseif mt == Engine.MessageType.SA_WALLPAPER_HOME_OFFSET then
    self:HomeChange(value)
  elseif mt == Engine.MessageType.SA_WALLPAPER_SET_REFRESH then
    self:SetRefresh(value)
  elseif mt == Engine.MessageType.SA_WALLPAPER_IS_LANDSCAPE then
    local prevIsLandscape = isLandscape
    isLandscape = value;
    if prevIsLandscape == 1 and isLandscape == 0 then
      curMainCameraDarkState = tgtMainCameraDarkState
    end
  elseif mt == Engine.MessageType.SA_WALLPAPER_MESSAGE_NOT_MATCH then
    LOGI("message not match !")
  end
end


function CamBehavior:SwitchTo(state, substate, force)
  substate = substate or 1 --substate默认为1
  force = force~=nil and force or false --force默认为false
  
  curTotalTransTime = 0
  isReduceFrameRate = false

  local tgtState = state
  if self.curState ~= tgtState then
    -- 更新状态
    expectHomeChanging = false
    self.prevState = self.curState
    self.curState = tgtState
    self.goStop = 5

    -- 切换至AOD状态
    if tgtState == State.AOD then

      if force then
        self.forceAni = true
      else
        self.forceAni = false
      end

      tgtMainCameraPos = aodMainCameraPos
      tgtMainCameraRot = aodMainCameraRot
      tgtMainCameraDarkState = aodMainCameraDarkState
      tgtMainCameraRotValue = aodMainCameraRotValue
      tgtMainCameraStateValue = aodMainCameraStateValue
      tgtMainCameraTargetHighValue = aodMainCameraTargetHighValue
      tgtMainCameraLookAt = aodMainCameraLookAt

      self.targetHeight = 0
      -- 不同分辨率下进入aod的初始高度不同
      if self.screenHeight > 3000 then
        if substate == 0 then
          self.targetHeight = 8
        elseif substate == 1 then
          self.targetHeight = 0.5
        elseif substate == 2 then
          self.targetHeight = -7
        end
      elseif self.screenHeight > 2600 then
        if substate == 0 then
          self.targetHeight = 9
        elseif substate == 1 then
          self.targetHeight = 1.5
        elseif substate == 2 then
          self.targetHeight = -6
        end
      else
        if substate == 0 then
          self.targetHeight = 6
        elseif substate == 1 then
          self.targetHeight = -1
        elseif substate == 2 then
          self.targetHeight = -8
        end
      end

      if self.curTimeSlice > 0 and self.curTimeSlice <= #self.Clouds and self.Clouds[self.curTimeSlice] then
        local ps = _GetComponentsInChildren(self.Clouds[self.curTimeSlice], Engine.ParticleComponent:RTTI())
        for i=1,#ps do
          ps[i]:stop()
          --ps[i]:clear()
        end
      end
    -- 切换至LOCK状态
    elseif tgtState == State.LOCK then

      if force then
        self.forceAni = true
      else
        self.forceAni = false
      end

      tgtMainCameraPos = lockMainCameraPos
      tgtMainCameraRot = lockMainCameraRot
      tgtMainCameraDarkState = lockMainCameraDarkState
      tgtMainCameraRotValue = lockMainCameraRotValue
      tgtMainCameraStateValue = lockMainCameraStateValue
      tgtMainCameraTargetHighValue = lockMainCameraTargetHighValue
      tgtMainCameraLookAt = lockMainCameraLookAt

      if self.curTimeSlice > 0 and self.curTimeSlice <= #self.Clouds and self.Clouds[self.curTimeSlice] then
        local ps = _GetComponentsInChildren(self.Clouds[self.curTimeSlice], Engine.ParticleComponent:RTTI())
        if self.curTimeSlice >= 1 and self.curTimeSlice <=3 then
          for i=1, #ps do
            for j = 1, #self.homeplayps do
              if self.homeplayps[j] == ps[i] then
                ps[i]:stop()
                --ps[i]:clear()
              end
            end
          end
        end
        
      end
    -- 切换至桌面状态
    elseif tgtState == State.HOME then

      if force then
        self.forceAni = true
      else
        self.forceAni = false
      end

      tgtMainCameraPos = homeMainCameraPos
      tgtMainCameraRot = homeMainCameraRot
      tgtMainCameraDarkState = homeMainCameraDarkState
      tgtMainCameraRotValue = homeMainCameraRotValue
      tgtMainCameraStateValue = homeMainCameraStateValue
      tgtMainCameraTargetHighValue = homeMainCameraTargetHighValue
      tgtMainCameraLookAt = homeMainCameraLookAt
      

      if self.curTimeSlice > 0 and self.curTimeSlice <= #self.Clouds and self.Clouds[self.curTimeSlice] then
        local ps = _GetComponentsInChildren(self.Clouds[self.curTimeSlice], Engine.ParticleComponent:RTTI())
        for i=1,#ps do
          ps[i]:play()
        end
      end
    end
  end

end



function CamBehavior:quartIn(per)
  return self:getEaseRatio(per, 2, 3)
end

function CamBehavior:getEaseRatio(p, type, power)
  local r = nil

  if type == 1.0 then
      r = 1.0 - p
  elseif type == 2.0 then
      r = p
  else
    if p < 0.5 then
        r = p * 2.0
    else
        r = (1.0 - p) * 2.0
    end
  end

  if power == 1.0 then
    r = math.pow(r, 2)
  elseif power == 2.0 then
    r = math.pow(r, 3)
  elseif power == 3.0 then
    r = math.pow(r, 4)
  elseif power == 4.0 then
    r = math.pow(r, 5)
  end

  -- return 
  if type == 1.0 then
    return 1.0 - r
  elseif type == 2.0 then
    return r
  else
    if p < 0.5 then
        return r / 2.0
    else
        return 1.0 - (r / 2.0)
    end
  end

end



function CamBehavior:SetAutoFrameRate(val)
  self.autoFrameRate = val
end
function CamBehavior:GetAutoFrameRate() 
  return self.autoFrameRate
end

function CamBehavior:SetStartRate(val)
  self.rateValue = val
end
function CamBehavior:GetStartRate() 
  return self.rateValue
end

function CamBehavior:SetDarkState(val)
  self.darkState = val
end
function CamBehavior:GetDarkState()
  return self.darkState
end

function CamBehavior:SetStateValue(val)
  self.stateValue = val
end
function CamBehavior:GetStateValue()
  return self.stateValue
end

function CamBehavior:SetRotValue(val)
  self.rotValue = val
end
function CamBehavior:GetRotValue()
  return self.rotValue
end

function CamBehavior:SetTargetHeightValue(val)
  self.targetHeightValue = val
end
function CamBehavior:GetTargetHeightValue()
  return self.targetHeightValue
end






function CamBehavior:GetLookAtX()
  return self.lookAt.x
end
function CamBehavior:SetLookAtX(v)
  self.lookAt.x = v
end
function CamBehavior:GetLookAtY()
  return self.lookAt.y
end
function CamBehavior:SetLookAtY(v)
  self.lookAt.y = v
end
function CamBehavior:GetLookAtZ()
  return self.lookAt.z
end
function CamBehavior:SetLookAtZ(v)
  self.lookAt.z = v
end

CamBehavior:MemberRegister("lookAtX",
  Core.ScriptTypes.FloatType(
    0.0, 0.5,
    CamBehavior.GetLookAtX,
    CamBehavior.SetLookAtX
));

CamBehavior:MemberRegister("lookAtY",
  Core.ScriptTypes.FloatType(
    0.0, 0.5,
    CamBehavior.GetLookAtY,
    CamBehavior.SetLookAtY
));

CamBehavior:MemberRegister("lookAtZ",
  Core.ScriptTypes.FloatType(
    0.0, 0.5,
    CamBehavior.GetLookAtZ,
    CamBehavior.SetLookAtZ
));

CamBehavior:MemberRegister("Dark State",
  Core.ScriptTypes.FloatType(
    0.0, 1.0,
    CamBehavior.GetDarkState,
    CamBehavior.SetDarkState
));

CamBehavior:MemberRegister("Rot Value",
  Core.ScriptTypes.FloatType(
    0.0, 1.0,
    CamBehavior.GetRotValue,
    CamBehavior.SetRotValue
));

CamBehavior:MemberRegister("State Value",
  Core.ScriptTypes.FloatType(
    0.0, 1.0,
    CamBehavior.GetStateValue,
    CamBehavior.SetStateValue
));

CamBehavior:MemberRegister("Target Height Value",
  Core.ScriptTypes.FloatType(
    0.0, 1.0,
    CamBehavior.GetTargetHeightValue,
    CamBehavior.SetTargetHeightValue
));




-- CamBehavior:MemberRegister("Auto Frame Rate",
--   Core.ScriptTypes.CheckBoxType(
--     CamBehavior.GetAutoFrameRate,
--     CamBehavior.SetAutoFrameRate
-- ))

-- CamBehavior:MemberRegister("Start Rate",
--   Core.ScriptTypes.IntType(
--     1, 3,
--     CamBehavior.GetStartRate,
--     CamBehavior.SetStartRate
-- ))










return CamBehavior
