#import <Foundation/Foundation.h>

@class ARTrack;

extern NSString *const ARStoreDidChangeNotification;

/// Saved and recently played YouTube tracks, kept in NSUserDefaults.
@interface ARStore : NSObject

+ (instancetype)shared;

@property (nonatomic, readonly) NSArray<ARTrack *> *savedTracks;
@property (nonatomic, readonly) NSArray<ARTrack *> *recentTracks;

- (BOOL)isSaved:(ARTrack *)track;
- (void)toggleSaved:(ARTrack *)track;
- (void)addRecent:(ARTrack *)track;
- (void)clearRecent;

@end
