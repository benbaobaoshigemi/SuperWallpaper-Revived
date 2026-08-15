'use strict';

const SWITCH_TO_RVA = 0x9992c8;
const SET_POS_RVA = 0x99f11c;
const UPDATE_RVA = 0x99a290;
const CAMERA_STATE_OFFSET = 0x28;
const CAMERA_INDEX_OFFSET = 0x140;
const FORCE_CAMERA_INDEX_OFFSET = 0x144;
const AOD_TO_LAND_OFFSET = 0x274;

const il2cpp = Process.getModuleByName('libil2cpp.so');
const switchTo = new NativeFunction(il2cpp.base.add(SWITCH_TO_RVA), 'bool',
    ['pointer', 'int', 'int', 'pointer']);
const setPos = new NativeFunction(il2cpp.base.add(SET_POS_RVA), 'void',
    ['pointer', 'int', 'pointer']);

let camera = null;
let updateListener = null;

function snapshot(label, instance) {
    send({
        type: 'snapshot',
        label,
        camera: instance.toString(),
        state: instance.add(CAMERA_STATE_OFFSET).readS32(),
        cameraIndex: instance.add(CAMERA_INDEX_OFFSET).readS32(),
        forceCameraIndex: instance.add(FORCE_CAMERA_INDEX_OFFSET).readS32(),
        aodToLand: instance.add(AOD_TO_LAND_OFFSET).readU8()
    });
}

Interceptor.attach(il2cpp.base.add(SWITCH_TO_RVA), {
    onEnter(args) {
        camera = args[0];
        this.camera = camera;
        this.targetState = args[1].toInt32();
        this.aodOffset = args[2].toInt32();
        snapshot(`switchTo enter target=${this.targetState} offset=${this.aodOffset}`,
            this.camera);
    },
    onLeave(retval) {
        snapshot(`switchTo leave changed=${retval.toInt32()}`, this.camera);
    }
});

updateListener = Interceptor.attach(il2cpp.base.add(UPDATE_RVA), {
    onEnter(args) {
        if (camera === null) {
            camera = args[0];
            snapshot('captured from Update', camera);
            setImmediate(() => updateListener.detach());
        }
    }
});

rpc.exports = {
    snapshot() {
        if (camera === null) {
            throw new Error('CameraCtrl instance has not been observed');
        }
        snapshot('manual', camera);
        return true;
    },
    setlockstyle(index) {
        if (camera === null) {
            throw new Error('CameraCtrl instance has not been observed');
        }
        camera.add(CAMERA_INDEX_OFFSET).writeS32(index);
        setPos(camera, 1, ptr(0));
        snapshot(`manual lock style=${index}`, camera);
        return true;
    },
    switchlock(index) {
        if (camera === null) {
            throw new Error('CameraCtrl instance has not been observed');
        }
        camera.add(CAMERA_INDEX_OFFSET).writeS32(index);
        camera.add(AOD_TO_LAND_OFFSET).writeU8(0);
        const changed = switchTo(camera, 1, -1, ptr(0));
        snapshot(`manual switch lock style=${index} changed=${changed}`, camera);
        return changed;
    }
};

send({ type: 'ready', base: il2cpp.base.toString() });
