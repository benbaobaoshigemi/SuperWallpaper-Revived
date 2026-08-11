local CCore = require "CCore"
require "MICore.enhance.init"

assert(nil == CCore.Object);
CCore.Object = require "MICore.rtti.object"
CCore.RttiManager = require "MICore.rtti.rttimanager"
CCore.mpairs = require "MICore.rtti.mpairs"
CCore.isFilePath = require "MICore.rtti.membertypeFilePath"
CCore.ScriptTypes = require "MICore.rtti.types.init"
CCore.isNil= require "MICore.rtti.venusnil"

require "MICore.rtti.object_enhance"

CCore.Behavior = require "MICore.behavior.behavior"
CCore.LoadBehavior = require "MICore.behavior.behaviorloader"

CCore.sortPairs = require "MICore.sortpair"

return CCore;
