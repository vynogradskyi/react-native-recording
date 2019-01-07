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
  addRecordingEventListener: listener => eventEmitter.addListener('recording', listener),
  addDownsampledEventListener: listener => eventEmitter.addListener('downsampled', listener),
  addDemodulatedEventListener: listener => eventEmitter.addListener('demodulated', listener),
  addCountEventsEventListener: listener => eventEmitter.addListener('countEvents', listener)
}
