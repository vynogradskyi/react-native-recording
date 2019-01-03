import { NativeModules, NativeEventEmitter } from 'react-native'
const { Recording } = NativeModules
const eventEmitter = new NativeEventEmitter(Recording)

export default {
  // TODO: params check
  init: (options: {
    bufferSize: number,
    sampleRate: number,
    bitsPerChannel: 8 | 16,
    channelsPerFrame: 1 | 2,
  }) => Recording.init(options),
  start: () => Recording.start(),
  stop: () => Recording.stop(),
  startDownsample: () => Recording.startDownsample(),
  startDemodulate: () => Recording.startDemodulate(),
  startCountEvents: () => Recording.startCountEvents(),
  addRecordingEventListener: listener => eventEmitter.addListener('recording', listener),
  addDownsampleEventListener: listener => eventEmitter.addListener('downsample', listener),
  addDemodulateEventListener: listener => eventEmitter.addListener('demodulate', listener),
  addCountEventEventListener: listener => eventEmitter.addListener('countevents', listener)
}
