#import "ARTrackActions.h"
#import "ARTrack.h"
#import "ARPlayer.h"
#import "ARStore.h"
#import "ARTheme.h"
#import "ARMiniPlayerView.h"

@implementation ARTrackActions

+ (void)presentForTrack:(ARTrack *)track from:(UIViewController *)controller sourceView:(UIView *)sourceView {
    if (!track.playable) {
        [self showToast:@"This song is protected or in iCloud"];
        return;
    }
    UIAlertController *sheet = [UIAlertController alertControllerWithTitle:track.title message:track.subtitle preferredStyle:UIAlertControllerStyleActionSheet];
    [sheet addAction:[UIAlertAction actionWithTitle:@"Play Next" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
        [[ARPlayer shared] playNext:track];
        [self showToast:@"Playing next"];
    }]];
    [sheet addAction:[UIAlertAction actionWithTitle:@"Add to Queue" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
        [[ARPlayer shared] enqueue:track];
        [self showToast:@"Added to queue"];
    }]];
    if (track.source == ARTrackSourceYouTube) {
        BOOL saved = [[ARStore shared] isSaved:track];
        [sheet addAction:[UIAlertAction actionWithTitle:saved ? @"Remove from Saved" : @"Save" style:saved ? UIAlertActionStyleDestructive : UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
            [[ARStore shared] toggleSaved:track];
            [self showToast:saved ? @"Removed from Saved" : @"Saved"];
        }]];
    }
    [sheet addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
    sheet.popoverPresentationController.sourceView = sourceView;
    sheet.popoverPresentationController.sourceRect = sourceView.bounds;
    [controller presentViewController:sheet animated:YES completion:nil];
}

+ (void)showToast:(NSString *)message {
    UIWindow *window = [UIApplication sharedApplication].keyWindow;
    if (!window) return;
    UILabel *label = [UILabel new];
    label.text = message;
    label.font = [ARTheme fontOfSize:14 weight:UIFontWeightSemibold];
    label.textColor = [UIColor blackColor];
    label.textAlignment = NSTextAlignmentCenter;
    label.backgroundColor = [UIColor colorWithWhite:0.95 alpha:1];
    label.layer.cornerRadius = 17;
    label.clipsToBounds = YES;
    CGFloat width = ceil([label sizeThatFits:CGSizeZero].width) + 36;
    CGFloat bottom = 49 + ([ARPlayer shared].currentTrack ? [ARMiniPlayerView height] : 0) + 14;
    label.frame = CGRectMake((window.bounds.size.width - width) / 2, window.bounds.size.height - bottom - 34, width, 34);
    label.alpha = 0;
    label.transform = CGAffineTransformMakeScale(0.9, 0.9);
    [window addSubview:label];
    [UIView animateWithDuration:0.25 delay:0 usingSpringWithDamping:0.8 initialSpringVelocity:0 options:0 animations:^{
        label.alpha = 1;
        label.transform = CGAffineTransformIdentity;
    } completion:^(BOOL finished) {
        [UIView animateWithDuration:0.3 delay:1.4 options:0 animations:^{ label.alpha = 0; } completion:^(BOOL done) {
            [label removeFromSuperview];
        }];
    }];
}

@end
