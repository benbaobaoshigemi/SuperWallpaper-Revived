# -*- coding: utf-8 -*-

from shutil import copytree,ignore_patterns,rmtree,copyfile
import os
import sys
import platform

def getPlatformPath():
    platname = platform.system()
    if platname == "Windows":
        return "windows"
    elif platname == "Darwin":
        return "mac"
    elif platname == "linux":
        return "linux"
    else:
        return "windows"

ROOT_PATH = os.path.join("..", "..")
CONFIG_MATERIAL_PATH = os.path.join(ROOT_PATH, "data", "material")
matcPath = os.path.join(ROOT_PATH, "bin", "tools", getPlatformPath(), "matc")

defaultRoots = CONFIG_MATERIAL_PATH

roots = defaultRoots

if len(sys.argv) > 1:
    roots = sys.argv[1:]

platformList = [ "desktop", "mobile" ]
apiList = [ "opengl", "vulkan" ]

def processShader(shaderPath):
    print("processing %s" % shaderPath)
    materialPath = shaderPath.replace(".mat", "")
    if os.path.isdir(materialPath):
        rmtree(materialPath)
    elif os.path.isfile(materialPath):
        os.remove(materialPath)
    os.mkdir(materialPath)
    for i in range(len(platformList)):
        platform = platformList[i]
        for j in range(len(apiList)):
            api = apiList[j]
            platformMaterialPath = os.path.join(materialPath, platform + api + ".filamat")
            os.system("%s -p %s -o %s -a %s %s" % 
                (matcPath, platform, platformMaterialPath, api, shaderPath))
    print("finished %s" % shaderPath)

def processDir(rootDir):
    for root, dirs, files in os.walk(rootDir):
        shaderFiles = filter(lambda name : name.endswith(".mat"), files)
        for shaderFile in shaderFiles:
            shaderPath = os.path.join(root, shaderFile)
            processShader(shaderPath)

for root in roots:
    if os.path.isdir(root):
        processDir(root)
    elif os.path.isfile(root) and root.endswith(".mat"):
        processShader(root)
    else:
        print("invalid path: %s" % root)

print("GenFilamentMat done")

