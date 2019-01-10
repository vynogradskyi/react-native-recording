#import "Recording.h"

@implementation Recording {
    AudioQueueRef _queue;
    AudioQueueBufferRef _buffer;
    NSNumber *_audioData[65536];
    UInt32 _bufferSize;
    long downsampleFactor;
    long demodulateIndex;
}

int const downsampleConstant = 32;
NSTimer *downsampleTimer;
NSTimer *demodulateTimer;
NSTimer *countEventsTimer;

NSMutableArray *recordedAudioDataArray;
NSMutableArray *downsampledDataArray;
NSMutableArray *demodulatedDataArray;
NSMutableArray *demodulatedDataArrayOneMinute;

void inputCallback(
        void *inUserData,
        AudioQueueRef inAQ,
        AudioQueueBufferRef inBuffer,
        const AudioTimeStamp *inStartTime,
        UInt32 inNumberPacketDescriptions,
        const AudioStreamPacketDescription *inPacketDescs) {
    [(__bridge Recording *) inUserData processInputBuffer:inBuffer queue:inAQ];
}

RCT_EXPORT_MODULE()

RCT_EXPORT_METHOD(init:(NSDictionary *) options) {
    UInt32 bufferSize = options[@"bufferSize"] == nil ? 8192 : [options[@"bufferSize"] unsignedIntegerValue];
    _bufferSize = bufferSize;
    downsampleFactor = options[@"downsampleFactor"] == nil ? 32 : [options[@"downsampleFactor"] unsignedIntegerValue];
    demodulateIndex = options[@"demodulateIndex"] == nil ? 138 : [options[@"demodulateIndex"] unsignedIntegerValue];

    AudioStreamBasicDescription description;
    description.mReserved = 0;
    description.mSampleRate = options[@"sampleRate"] == nil ? 44100 : [options[@"sampleRate"] doubleValue];
    description.mBitsPerChannel = options[@"bitsPerChannel"] == nil ? 16 : [options[@"bitsPerChannel"] unsignedIntegerValue];
    description.mChannelsPerFrame = options[@"channelsPerFrame"] == nil ? 1 : [options[@"channelsPerFrame"] unsignedIntegerValue];
    description.mFramesPerPacket = options[@"framesPerPacket"] == nil ? 1 : [options[@"framesPerPacket"] unsignedIntegerValue];
    description.mBytesPerFrame = options[@"bytesPerFrame"] == nil ? 2 : [options[@"bytesPerFrame"] unsignedIntegerValue];
    description.mBytesPerPacket = options[@"bytesPerPacket"] == nil ? 2 : [options[@"bytesPerPacket"] unsignedIntegerValue];
    description.mFormatID = kAudioFormatLinearPCM;
    description.mFormatFlags = kAudioFormatFlagIsSignedInteger;

    AudioQueueNewInput(&description, inputCallback, (__bridge void *) self, NULL, NULL, 0, &_queue);
    AudioQueueAllocateBuffer(_queue, (UInt32) (bufferSize * 2), &_buffer);
    AudioQueueEnqueueBuffer(_queue, _buffer, 0, NULL);
    
    recordedAudioDataArray = [NSMutableArray array];
    downsampledDataArray = [NSMutableArray array];
    demodulatedDataArray = [NSMutableArray array];
    demodulatedDataArrayOneMinute = [NSMutableArray array];
    
    if (![NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            // downsampleTimer = [NSTimer scheduledTimerWithTimeInterval:1 target:self selector:@selector(sendDownSampledData) userInfo:nil repeats:YES];
            demodulateTimer = [NSTimer scheduledTimerWithTimeInterval:1 target:self selector:@selector(sendDemodulatedData) userInfo:nil repeats:YES];
            countEventsTimer = [NSTimer scheduledTimerWithTimeInterval:60 target:self selector:@selector(sendCountEventsData) userInfo:nil repeats:YES];
        });
    }
    else {
        // downsampleTimer = [NSTimer scheduledTimerWithTimeInterval:1 target:self
        //     selector:@selector(sendDownSampledData) userInfo:nil repeats:YES];
        demodulateTimer = [NSTimer scheduledTimerWithTimeInterval:1 target:self selector:@selector(sendDemodulatedData) userInfo:nil repeats:YES];
        countEventsTimer = [NSTimer scheduledTimerWithTimeInterval:60 target:self selector:@selector(sendCountEventsData) userInfo:nil repeats:YES];
    }
}

RCT_EXPORT_METHOD(start) {
    AudioQueueStart(_queue, NULL);
}

RCT_EXPORT_METHOD(stop) {
    AudioQueueStop(_queue, YES);
    [downsampleTimer invalidate];
    [demodulateTimer invalidate];
    [countEventsTimer invalidate];
}

- (void) startNew {
    AudioQueueStart(_queue, NULL);
}

- (void)processInputBuffer:(AudioQueueBufferRef)inBuffer queue:(AudioQueueRef)queue {
    SInt16 *audioData = inBuffer->mAudioData;
    UInt32 count = inBuffer->mAudioDataByteSize / sizeof(SInt16);
    for (int i = 0; i < _bufferSize; i++) {
        _audioData[i] = @(audioData[i]);
    }
    NSArray * array = [NSArray arrayWithObjects:_audioData count:count];
    [self downsampleData:[array copy]];

    AudioQueueEnqueueBuffer(queue, inBuffer, 0, NULL);
}

- (void)sendDemodulatedData {
    [self sendEventWithName:@"demodulated" body:demodulatedDataArray];
    [demodulatedDataArrayOneMinute addObjectsFromArray:[demodulatedDataArray copy]];
    [demodulatedDataArray removeAllObjects];
}

- (void)sendDownSampledData {
    [self sendEventWithName:@"downsampled" body:downsampledDataArray];
    [downsampledDataArray removeAllObjects];
}

- (void)sendCountEventsData {
    int eventsCount = [self countEvents:demodulatedDataArrayOneMinute];
    [self sendEventWithName:@"countEvents" body:@(eventsCount)];
    [demodulatedDataArrayOneMinute removeAllObjects];
}


- (NSArray *)downsampleData:(NSArray *)audioArray {
    
    int every = round(audioArray.count / downsampleFactor);
    NSMutableArray *arr = [NSMutableArray array];
    
    for (int i = 0; i < every; i++) {
        [arr addObject:audioArray[i * downsampleConstant]];
    }
    dispatch_sync(dispatch_get_main_queue(), ^{
        [downsampledDataArray addObjectsFromArray:[arr copy]];
    });
    [self demodulateData:[arr copy]];
    return arr;
}

- (NSArray *)demodulateData:(NSMutableArray *) downsampledData {
    
    NSMutableArray* demodulatedData = [NSMutableArray array];
    int index = 0;
    int max = 0;
    
    for (NSNumber *element in downsampledData) {
        if (index == demodulateIndex) {
            [demodulatedData addObject:@(max)];
            max = 0;
            index = 0;
        }
        max = [element intValue] > max ? [element intValue] : max;
        index++;
    }
    dispatch_sync(dispatch_get_main_queue(), ^{
        [demodulatedDataArray addObjectsFromArray:[demodulatedData copy]];
    });
    return demodulatedData;
}

- (int)countEvents:(NSMutableArray *) demodulatedData {
    
    enum states {
        FindFirst,
        Wait,
        FindEventStart,
        EventTop
    }
    
    const thresholdDecayRate = 0.985;  //threshold decay rate per sample
    const int holdCnt = 40;      //number of samples processed in hold-off State
    const int stateTwoLimit = 80;  //after STATE2LIMIT samples have occured reset the signal max value
    const int eventSize = 400;
    const int initialThreshold = 200;
    
    enum states currentState = FindFirst;
    int threshold = initialThreshold, eventCount = 0,
    maxSig, minSig, hold, stateTwoCnt;
    
    //for (NSArray *element in demodulatedData) {
    for (NSNumber *valueNumber in demodulatedData) {
        int value = [valueNumber intValue];
        switch (currentState) {
            case FindFirst:
                if (value > threshold) {
                    threshold = value;
                    eventCount++;
                    currentState = Wait;
                    maxSig = 0;
                    minSig = 10000;
                    hold = 0;
                }
                break;
                
            case Wait:
                if ((hold > holdCnt - 3) && (value > maxSig)) { maxSig = value; }
                if (value < minSig) { minSig = value; }
                
                hold++;
                
                if (hold > holdCnt) {
                    currentState = FindEventStart;
                }
                
                stateTwoCnt = 0;
                
                if (value > threshold) {
                    threshold = value;
                }
                threshold = threshold < initialThreshold ? initialThreshold : threshold * thresholdDecayRate;
                break;
                
            case FindEventStart:
                stateTwoCnt++;
                if (stateTwoCnt > stateTwoLimit) {
                    maxSig = 0;
                    currentState = FindFirst;
                } else {
                    if (value > maxSig) {
                        maxSig = value;
                    }
                    if (value < minSig) {
                        minSig = value;
                    }
                    if (value < threshold) {
                        threshold = threshold < initialThreshold ? initialThreshold : threshold * thresholdDecayRate;
                    } else {
                        threshold = value;
                        currentState = EventTop;
                    }
                }
                break;
                
            case EventTop:
                if (value > maxSig) {
                    maxSig = value;
                }
                if (value < minSig) {
                    minSig = value;
                }
                if (value > threshold) {
                    threshold = value;
                } else {
                    if (maxSig - minSig >= eventSize) { eventCount++; }
                    
                    currentState = Wait;
                    maxSig = 0;
                    minSig = 10000;
                    hold = 0;
                }
                break;
                
            default:
                break;
        }
    }
//    }
    NSLog(@"Events count = %d", eventCount);
    return eventCount;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[@"downsampled", @"demodulated", @"countEvents"];
}

- (void)dealloc {
    AudioQueueStop(_queue, YES);
   // [downsampleTimer invalidate];
    [demodulateTimer invalidate];
    [countEventsTimer invalidate];
}

@end
