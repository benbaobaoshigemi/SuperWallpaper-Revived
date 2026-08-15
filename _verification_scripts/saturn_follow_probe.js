'use strict';

const processName = 'com.miui.miwallpaper.saturn:saturnSuperWallpaper';
const module = Process.getModuleByName('libil2cpp.so');
const il2cppInit = module.getExportByName('il2cpp_init');
const cameraDeskChange = module.base.add(0x99ffc4);
const deskChange = module.base.add(0x9a00a0);
const landscapeUpdate = module.base.add(0x9a1264);
let logged = false;
let updateLogged = false;

function vector(instance, offset) {
    const address = instance.add(offset);
    return [address.readFloat(), address.add(4).readFloat(), address.add(8).readFloat()];
}

function dump(label, instance, input) {
    const landscape = instance.add(0xf0).readPointer();
    console.log(JSON.stringify({
        label,
        processName,
        input,
        instance: instance.toString(),
        cameraOffset: instance.add(0x22c).readFloat(),
        curCamRot: instance.add(0x340).readFloat(),
        targetCamRot: instance.add(0x344).readFloat(),
        curZoom: instance.add(0x354).readFloat(),
        targetZoom: instance.add(0x358).readFloat(),
        landscape: landscape.toString(),
        scaleOffset: landscape.isNull() ? null : vector(landscape, 0x38),
        rotOffset: landscape.isNull() ? null : vector(landscape, 0x50),
        posOffset: landscape.isNull() ? null : vector(landscape, 0x5c),
    }));
}

Interceptor.attach(cameraDeskChange, {
    onEnter(args) {
        if (logged) return;
        logged = true;
        dump('CameraCtrl.DeskChange', args[0], args[1].toFloat());
    },
});

Interceptor.attach(deskChange, {
    onEnter(args) {
        console.log(JSON.stringify({
            label: 'LandscapeRotate.DeskChange',
            processName,
            input: args[1].toFloat(),
            instance: args[0].toString(),
            scaleOffset: vector(args[0], 0x38),
            rotOffset: vector(args[0], 0x50),
            posOffset: vector(args[0], 0x5c),
        }));
    },
});

Interceptor.attach(landscapeUpdate, {
    onEnter(args) {
        if (updateLogged) return;
        updateLogged = true;
        console.log(JSON.stringify({
            label: 'LandscapeRotate.Update',
            instance: args[0].toString(),
            scaleOffset: vector(args[0], 0x38),
            rotOffset: vector(args[0], 0x50),
            posOffset: vector(args[0], 0x5c),
        }));
    },
});

console.log('saturn follow probe attached base=' + module.base
    + ' il2cpp_init_rva=' + il2cppInit.sub(module.base));
