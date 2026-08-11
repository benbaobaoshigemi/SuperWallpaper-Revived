
local Math = require("CMath")
local Engine = require("CEngine")
local Core = require("MICore")
local SceneSequence = require("framework.engine.SceneSequence")

require "framework.engine.GameObject"

if _EDITOR then
  function Engine.Scene:Init()
    self:setSequence(SceneSequence.HIGH)
    self:createDefaultRenderTarget(Math.int2(128,128));
  end

  function Engine.Scene:CreateGrid()
    local cellLength = 1.0;
    local cellCnt = 100;
    local material = "comm:material/editor/grid.mat.meta";
    local sidelength = cellLength;
    local size = cellCnt;

    local gridNode = self:CreateGenericNode("Grid");
    gridNode:setLayer(Engine.LayerMask.MC_MASK_EDITOR_SCENE_LAYER);

    local renderComponent = gridNode:createComponent("RenderComponent");
    renderComponent:eraseRenderProperty(Engine.GraphicDefine.RP_CULL);
    renderComponent:setRenderProperty(Engine.GraphicDefine.RP_IGNORE_PICK);

    local vertexStream = Engine.VertexStream();
    local indexStream = Engine.IndicesStream();
    vertexStream:setVertexType(Core.RHIDefine.PS_ATTRIBUTE_POSITION,
      Core.RHIDefine.DT_FLOAT,
      Core.RHIDefine.DT_FLOAT,
      4);
    indexStream:setIndicesType(
      Core.RHIDefine.IT_UINT16);

    vertexStream:reserveBuffer(4 * size);--预先分配顶点的内存  4*n
    indexStream:reserveBuffer(4 * size + 4);--4*n+4
    local val=size / 2;

    for i=-val, val do
      vertexStream:pushVertexData(
        Core.RHIDefine.PS_ATTRIBUTE_POSITION,
        Math.float4(i*sidelength,0,-val*sidelength,1));
      vertexStream:pushVertexData(
        Core.RHIDefine.PS_ATTRIBUTE_POSITION,
        Math.float4(i*sidelength,0,val*sidelength,1));
    end

    for i = 1-val, (val-1) do
      vertexStream:pushVertexData(
        Core.RHIDefine.PS_ATTRIBUTE_POSITION,
        Math.float4(-val*sidelength,0,i*sidelength,1));
      vertexStream:pushVertexData(
        Core.RHIDefine.PS_ATTRIBUTE_POSITION,
        Math.float4(val*sidelength,0,i*sidelength,1));
    end

    for i=0,4*size-1 do
      indexStream:pushIndicesData(i);
    end
    indexStream:pushIndicesData(0);
    indexStream:pushIndicesData(2*size);
    indexStream:pushIndicesData(1);
    indexStream:pushIndicesData(2*size+1);

    local mat = Engine.MaterialEntity()
    mat:pushMetadata(Engine.MaterialMetadata(material));
    mat:createResource();
    renderComponent:addMaterialEntity(mat);

    local vmeta = Engine.ReferenceVertexMetadata(Core.RHIDefine.MU_STATIC, vertexStream)
    local imeta = Engine.ReferenceIndicesMetadata(Core.RHIDefine.MU_STATIC, indexStream)
    renderComponent:pushMetadata(Engine.RenderObjectMeshMetadata(
      Core.RHIDefine.RM_LINES,
      vmeta,
      imeta))
    renderComponent:createRenderResource();

    renderComponent:setParameter("gridColor", Math.float4(0.5, 0.5, 0.5, 1.0));

    return gridNode;
  end

  function Engine.Scene:CreateEditorCamera()
    local size = Math.int2(128,128);
    local near = 0.1;
    local far = 1000;
    local pos = Math.float3(0,1,4);
    local lookat = Math.float3(0,0,0);
    local up = Math.float3(0,1,0);
    local EditorCameraName = "EditorCamera";

    local rendertarget = Engine.RenderTargetEntity();--创建一个FBO
    rendertarget:pushMetadata(--设置FBO格式
      Engine.RenderTargetMetadata(
        Core.RHIDefine.RT_RENDER_TARGET_2D,
        Core.RHIDefine.ST_SWAP_UNIQUE,
        Math.int4(0,0,size.x,size.y),--视口大小
        size, 1, false));--分辨率
    local depth = rendertarget:makeTextureAttachment(Core.RHIDefine.TA_DEPTH_STENCIL);--增加深度纹理
        depth:pushMetadata(
          Engine.DepthRenderBufferMetadata(
            Core.RHIDefine.ST_SWAP_UNIQUE,
            size,
            Core.RHIDefine.PF_DEPTH32
          ));--增加深度
    local outputtexture = rendertarget:makeTextureAttachment(Core.RHIDefine.TA_COLOR_0);--增加color0纹理
    outputtexture:pushMetadata(--创建纹理
          Engine.TextureBufferMetadata(size));
    rendertarget:createResource();

    local luacamera3D = self:CreateGenericNode("EditorCamera");
    local cameraComponent = luacamera3D:createComponent("CameraComponent");
    local camera3D = luacamera3D;
    camera3D:setLayer(Engine.LayerMask.MC_MASK_EDITOR_SCENE_LAYER);
    camera3D.Name = EditorCameraName  --给一个默认的名字

    cameraComponent:changeResolution(size);
    cameraComponent:setLayerMaskEverything();
    cameraComponent:createPerspectiveProjection(cameraComponent:getFov(), size.x/size.y, near,far);
    cameraComponent:lookAt(pos, lookat, up);
    cameraComponent:attachRenderTarget(rendertarget);
    cameraComponent:recalculate();
    cameraComponent:setActive(true);
    cameraComponent:setFrustumShow(false);

    if (self:getRenderSettings():getSkyBoxDirectory() ~= "") then
      cameraComponent:setPostProcessingEnabled(true);
    end

    return luacamera3D;
  end
end

function Engine.Scene:CreateGenericNode(nname)
  local node = self:createObject(nname);
  local trans = node:createComponent("TransformComponent");
  --trans:setLocalPosition(Math.float3(0.0,0.0,0.0));
  return node;
end
