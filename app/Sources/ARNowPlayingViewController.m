#import "ARNowPlayingViewController.h"
#import "ARQueueViewController.h"
#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARStore.h"
#import "ARTheme.h"
#import "ARIcons.h"
#import "ARArtwork.h"
#import <MediaPlayer/MediaPlayer.h>

@interface ARScrubber : UISlider
@end

@implementation ARScrubber
// A taller touch area than the thin track suggests, so it's easy to grab.
- (BOOL)pointInside:(CGPoint)point withEvent:(UIEvent *)event {
    return CGRectContainsPoint(CGRectInset(self.bounds, -8, -14), point);
}
@end

@interface ARNowPlayingViewController () <UIGestureRecognizerDelegate>
@end

@implementation ARNowPlayingViewController {
    UIImageView *_backdrop;
    UIVisualEffectView *_blur;
    UIView *_shade;
    UIView *_card;
    UIImageView *_artwork;
    UIButton *_closeButton;
    UILabel *_titleLabel;
    UILabel *_artistLabel;
    UIButton *_heartButton;
    ARScrubber *_scrubber;
    UILabel *_elapsedLabel;
    UILabel *_remainingLabel;
    UIButton *_shuffleButton;
    UIButton *_previousButton;
    UIButton *_playButton;
    UIButton *_nextButton;
    UIButton *_repeatButton;
    UIActivityIndicatorView *_spinner;
    MPVolumeView *_volume;
    UIImageView *_volumeLow;
    UIImageView *_volumeHigh;
    UIButton *_queueButton;
    ARTrack *_shownTrack;
    NSUInteger _shownVersion;
    BOOL _scrubbing;
    BOOL _artworkExpanded;
}

- (instancetype)init {
    if ((self = [super init])) {
        self.modalPresentationStyle = UIModalPresentationOverFullScreen;
        self.modalPresentationCapturesStatusBarAppearance = YES;
    }
    return self;
}

- (UIButton *)iconButton:(ARIcon)icon size:(CGFloat)size action:(SEL)action {
    UIButton *b = [UIButton buttonWithType:UIButtonTypeSystem];
    [b setImage:[ARIcons icon:icon size:size] forState:UIControlStateNormal];
    b.tintColor = [ARTheme textPrimary];
    [b addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:b];
    return b;
}

- (UILabel *)label:(UIFont *)font color:(UIColor *)color {
    UILabel *l = [UILabel new];
    l.font = font;
    l.textColor = color;
    [self.view addSubview:l];
    return l;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [ARTheme background];
    self.view.clipsToBounds = YES;

    _backdrop = [UIImageView new];
    _backdrop.contentMode = UIViewContentModeScaleAspectFill;
    _backdrop.clipsToBounds = YES;
    [self.view addSubview:_backdrop];
    _blur = [[UIVisualEffectView alloc] initWithEffect:[UIBlurEffect effectWithStyle:UIBlurEffectStyleDark]];
    [self.view addSubview:_blur];
    _shade = [UIView new];
    _shade.backgroundColor = [UIColor colorWithWhite:0 alpha:0.3];
    [self.view addSubview:_shade];

    _closeButton = [self iconButton:ARIconChevronDown size:26 action:@selector(close)];
    _closeButton.tintColor = [UIColor colorWithWhite:1 alpha:0.7];

    _card = [UIView new];
    _card.layer.shadowColor = [UIColor blackColor].CGColor;
    _card.layer.shadowOpacity = 0.5;
    _card.layer.shadowRadius = 18;
    _card.layer.shadowOffset = CGSizeMake(0, 10);
    [self.view addSubview:_card];
    _artwork = [UIImageView new];
    _artwork.contentMode = UIViewContentModeScaleAspectFill;
    _artwork.clipsToBounds = YES;
    _artwork.layer.cornerRadius = 10;
    [_card addSubview:_artwork];

    _titleLabel = [self label:[ARTheme fontOfSize:20 weight:UIFontWeightBold] color:[ARTheme textPrimary]];
    _artistLabel = [self label:[ARTheme fontOfSize:17 weight:UIFontWeightRegular] color:[UIColor colorWithWhite:1 alpha:0.6]];
    _heartButton = [self iconButton:ARIconHeart size:24 action:@selector(toggleSaved)];

    _scrubber = [ARScrubber new];
    [_scrubber setThumbImage:[ARTheme circleImageWithColor:[UIColor whiteColor] diameter:12] forState:UIControlStateNormal];
    [_scrubber setThumbImage:[ARTheme circleImageWithColor:[UIColor whiteColor] diameter:22] forState:UIControlStateHighlighted];
    [_scrubber setMinimumTrackImage:[ARTheme pillImageWithColor:[UIColor whiteColor] height:4] forState:UIControlStateNormal];
    [_scrubber setMaximumTrackImage:[ARTheme pillImageWithColor:[UIColor colorWithWhite:1 alpha:0.2] height:4] forState:UIControlStateNormal];
    [_scrubber addTarget:self action:@selector(scrubStart) forControlEvents:UIControlEventTouchDown];
    [_scrubber addTarget:self action:@selector(scrubMove) forControlEvents:UIControlEventValueChanged];
    [_scrubber addTarget:self action:@selector(scrubEnd) forControlEvents:UIControlEventTouchUpInside | UIControlEventTouchUpOutside | UIControlEventTouchCancel];
    [self.view addSubview:_scrubber];

    UIFont *digits = [ARTheme digitsFontOfSize:12 weight:UIFontWeightMedium];
    _elapsedLabel = [self label:digits color:[UIColor colorWithWhite:1 alpha:0.55]];
    _remainingLabel = [self label:digits color:[UIColor colorWithWhite:1 alpha:0.55]];
    _remainingLabel.textAlignment = NSTextAlignmentRight;

    _shuffleButton = [self iconButton:ARIconShuffle size:22 action:@selector(toggleShuffle)];
    _previousButton = [self iconButton:ARIconPrevious size:34 action:@selector(previous)];
    _playButton = [self iconButton:ARIconPlay size:46 action:@selector(togglePlay)];
    _nextButton = [self iconButton:ARIconNext size:34 action:@selector(next)];
    _repeatButton = [self iconButton:ARIconRepeat size:22 action:@selector(cycleRepeat)];
    _spinner = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleWhiteLarge];
    _spinner.userInteractionEnabled = NO;
    [self.view addSubview:_spinner];

    UIColor *dim = [UIColor colorWithWhite:1 alpha:0.5];
    _volumeLow = [[UIImageView alloc] initWithImage:[ARIcons icon:ARIconSpeakerLow size:16 color:dim]];
    _volumeHigh = [[UIImageView alloc] initWithImage:[ARIcons icon:ARIconSpeakerHigh size:16 color:dim]];
    [self.view addSubview:_volumeLow];
    [self.view addSubview:_volumeHigh];
    _volume = [MPVolumeView new];
    _volume.showsRouteButton = NO;
    [_volume setVolumeThumbImage:[ARTheme circleImageWithColor:[UIColor whiteColor] diameter:16] forState:UIControlStateNormal];
    [_volume setMinimumVolumeSliderImage:[ARTheme pillImageWithColor:[UIColor colorWithWhite:1 alpha:0.85] height:4] forState:UIControlStateNormal];
    [_volume setMaximumVolumeSliderImage:[ARTheme pillImageWithColor:[UIColor colorWithWhite:1 alpha:0.2] height:4] forState:UIControlStateNormal];
    [self.view addSubview:_volume];

    _queueButton = [self iconButton:ARIconQueue size:22 action:@selector(showQueue)];

    UIPanGestureRecognizer *pan = [[UIPanGestureRecognizer alloc] initWithTarget:self action:@selector(pan:)];
    pan.delegate = self;
    [self.view addGestureRecognizer:pan];

    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    [nc addObserver:self selector:@selector(update) name:ARPlayerDidChangeNotification object:nil];
    [nc addObserver:self selector:@selector(updateTime) name:ARPlayerTimeNotification object:nil];
    [nc addObserver:self selector:@selector(update) name:ARStoreDidChangeNotification object:nil];
    [self update];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    CGFloat W = self.view.bounds.size.width, H = self.view.bounds.size.height;
    _backdrop.frame = CGRectInset(self.view.bounds, -40, -40);
    _blur.frame = self.view.bounds;
    _shade.frame = self.view.bounds;

    _closeButton.frame = CGRectMake((W - 60) / 2, 20, 60, 36);

    CGFloat art = MIN(W - 48, H - 320);
    CGFloat y = 64;
    CGRect cardFrame = CGRectMake((W - art) / 2, y, art, art);
    CGAffineTransform t = _card.transform;
    _card.transform = CGAffineTransformIdentity;
    _card.frame = cardFrame;
    _card.transform = t;
    _artwork.frame = _card.bounds;
    _card.layer.shadowPath = [UIBezierPath bezierPathWithRoundedRect:_card.bounds cornerRadius:10].CGPath;
    y += art + 22;

    CGFloat m = 24;
    _heartButton.frame = CGRectMake(W - m - 34, y + 4, 40, 40);
    CGFloat textWidth = _heartButton.hidden ? W - m * 2 : W - m * 2 - 40;
    _titleLabel.frame = CGRectMake(m, y, textWidth, 25);
    _artistLabel.frame = CGRectMake(m, y + 26, textWidth, 22);
    y += 48 + 14;

    _scrubber.frame = CGRectMake(m, y, W - m * 2, 22);
    _elapsedLabel.frame = CGRectMake(m, y + 24, 80, 15);
    _remainingLabel.frame = CGRectMake(W - m - 80, y + 24, 80, 15);
    y += 46;

    CGFloat cy = y + 34;
    _playButton.frame = CGRectMake(0, 0, 72, 72);
    _playButton.center = CGPointMake(W / 2, cy);
    _spinner.center = _playButton.center;
    _previousButton.frame = CGRectMake(0, 0, 60, 60);
    _previousButton.center = CGPointMake(W / 2 - 82, cy);
    _nextButton.frame = CGRectMake(0, 0, 60, 60);
    _nextButton.center = CGPointMake(W / 2 + 82, cy);
    _shuffleButton.frame = CGRectMake(m - 10, cy - 22, 44, 44);
    _repeatButton.frame = CGRectMake(W - m - 34, cy - 22, 44, 44);
    y += 68 + 14;

    CGFloat vy = MAX(y, H - 48);
    _queueButton.frame = CGRectMake(W - m - 34, vy - 5, 44, 44);
    CGFloat vRight = W - m - 48;
    _volumeLow.frame = CGRectMake(m, vy + 9, 16, 16);
    _volumeHigh.frame = CGRectMake(vRight - 16, vy + 9, 16, 16);
    _volume.frame = CGRectMake(m + 26, vy + 6, vRight - 16 - 10 - (m + 26), 22);
}

#pragma mark - Updates

- (void)update {
    ARPlayer *player = [ARPlayer shared];
    ARTrack *track = player.currentTrack;
    if (!track) {
        [self close];
        return;
    }
    _titleLabel.text = track.title;
    if (player.errorMessage) {
        _artistLabel.text = player.errorMessage;
        _artistLabel.textColor = [ARTheme accent];
    } else {
        _artistLabel.text = track.subtitle;
        _artistLabel.textColor = [UIColor colorWithWhite:1 alpha:0.6];
    }

    BOOL youtube = track.source == ARTrackSourceYouTube;
    BOOL heartWasHidden = _heartButton.hidden;
    _heartButton.hidden = !youtube;
    BOOL saved = [[ARStore shared] isSaved:track];
    [_heartButton setImage:[ARIcons icon:saved ? ARIconHeartFilled : ARIconHeart size:24] forState:UIControlStateNormal];
    _heartButton.tintColor = saved ? [ARTheme accent] : [UIColor colorWithWhite:1 alpha:0.7];
    if (heartWasHidden != _heartButton.hidden) [self.view setNeedsLayout];

    BOOL buffering = player.buffering;
    [_playButton setImage:buffering ? nil : [ARIcons icon:player.playing ? ARIconPause : ARIconPlay size:46] forState:UIControlStateNormal];
    if (buffering) [_spinner startAnimating];
    else [_spinner stopAnimating];

    _shuffleButton.tintColor = player.shuffle ? [ARTheme accent] : [UIColor colorWithWhite:1 alpha:0.55];
    [_repeatButton setImage:[ARIcons icon:player.repeatMode == ARRepeatOne ? ARIconRepeatOne : ARIconRepeat size:22] forState:UIControlStateNormal];
    _repeatButton.tintColor = player.repeatMode != ARRepeatOff ? [ARTheme accent] : [UIColor colorWithWhite:1 alpha:0.55];
    _queueButton.tintColor = [UIColor colorWithWhite:1 alpha:0.7];

    if (track != _shownTrack || player.metadataVersion != _shownVersion) {
        BOOL sameTrack = track == _shownTrack;
        _shownTrack = track;
        _shownVersion = player.metadataVersion;
        UIImage *cached = [ARArtwork cachedArtworkForTrack:track size:300];
        if (cached || !sameTrack) [self showArtwork:cached ?: [ARTheme placeholderArtworkOfSize:300] backdrop:cached animated:sameTrack];
        if (!cached) {
            [ARArtwork loadArtworkForTrack:track size:300 completion:^(UIImage *image) {
                if (image && self->_shownTrack == track) [self showArtwork:image backdrop:image animated:YES];
            }];
        }
    }
    [self setArtworkExpanded:player.playing || buffering animated:self.view.window != nil];
    [self updateTime];
}

- (void)showArtwork:(UIImage *)image backdrop:(UIImage *)backdrop animated:(BOOL)animated {
    void (^apply)(void) = ^{
        self->_artwork.image = image;
        self->_backdrop.image = backdrop;
    };
    if (!animated) return apply();
    [UIView transitionWithView:self.view duration:0.3 options:UIViewAnimationOptionTransitionCrossDissolve | UIViewAnimationOptionAllowUserInteraction animations:apply completion:nil];
}

- (void)setArtworkExpanded:(BOOL)expanded animated:(BOOL)animated {
    if (expanded == _artworkExpanded && animated) return;
    _artworkExpanded = expanded;
    CGAffineTransform t = expanded ? CGAffineTransformIdentity : CGAffineTransformMakeScale(0.84, 0.84);
    if (!animated) {
        _card.transform = t;
        _card.layer.shadowOpacity = expanded ? 0.5 : 0.25;
        return;
    }
    [UIView animateWithDuration:0.55 delay:0 usingSpringWithDamping:0.7 initialSpringVelocity:0 options:UIViewAnimationOptionBeginFromCurrentState | UIViewAnimationOptionAllowUserInteraction animations:^{
        self->_card.transform = t;
    } completion:nil];
    _card.layer.shadowOpacity = expanded ? 0.5 : 0.25;
}

- (void)updateTime {
    if (_scrubbing) return;
    ARPlayer *player = [ARPlayer shared];
    NSTimeInterval d = player.duration, t = player.currentTime;
    _scrubber.maximumValue = MAX(d, 1);
    _scrubber.value = t;
    _scrubber.enabled = d > 0;
    [self showTimes:t duration:d];
}

- (void)showTimes:(NSTimeInterval)t duration:(NSTimeInterval)d {
    _elapsedLabel.text = [ARTheme formatTime:t];
    _remainingLabel.text = d > 0 ? [@"-" stringByAppendingString:[ARTheme formatTime:MAX(0, d - t)]] : @"--:--";
}

#pragma mark - Actions

- (void)close {
    if (self.presentingViewController && !self.isBeingDismissed) [self dismissViewControllerAnimated:YES completion:nil];
}

- (void)togglePlay { [[ARPlayer shared] togglePlayPause]; }
- (void)next { [[ARPlayer shared] next]; }
- (void)previous { [[ARPlayer shared] previous]; }
- (void)toggleShuffle { [ARPlayer shared].shuffle = ![ARPlayer shared].shuffle; }

- (void)cycleRepeat {
    ARPlayer *player = [ARPlayer shared];
    player.repeatMode = (player.repeatMode + 1) % 3;
}

- (void)toggleSaved {
    ARTrack *track = [ARPlayer shared].currentTrack;
    if (track) [[ARStore shared] toggleSaved:track];
    _heartButton.transform = CGAffineTransformMakeScale(0.7, 0.7);
    [UIView animateWithDuration:0.4 delay:0 usingSpringWithDamping:0.4 initialSpringVelocity:0 options:0 animations:^{
        self->_heartButton.transform = CGAffineTransformIdentity;
    } completion:nil];
}

- (void)showQueue {
    UINavigationController *nav = [[UINavigationController alloc] initWithRootViewController:[ARQueueViewController new]];
    [self presentViewController:nav animated:YES completion:nil];
}

- (void)scrubStart { _scrubbing = YES; }

- (void)scrubMove {
    [self showTimes:_scrubber.value duration:[ARPlayer shared].duration];
}

- (void)scrubEnd {
    [[ARPlayer shared] seekTo:_scrubber.value];
    // Let the seek land before time updates move the knob again.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.4 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        self->_scrubbing = NO;
    });
}

- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gesture shouldReceiveTouch:(UITouch *)touch {
    // Leave the scrubber and the volume slider alone.
    for (UIView *v = touch.view; v; v = v.superview) {
        if ([v isKindOfClass:[UISlider class]] || v == _volume) return NO;
    }
    return YES;
}

- (BOOL)gestureRecognizerShouldBegin:(UIPanGestureRecognizer *)pan {
    CGPoint v = [pan velocityInView:self.view];
    return v.y > fabs(v.x);
}

- (void)pan:(UIPanGestureRecognizer *)pan {
    CGFloat dy = MAX(0, [pan translationInView:self.view].y);
    switch (pan.state) {
        case UIGestureRecognizerStateChanged:
            self.view.transform = CGAffineTransformMakeTranslation(0, dy);
            break;
        case UIGestureRecognizerStateEnded:
        case UIGestureRecognizerStateCancelled: {
            CGFloat velocity = [pan velocityInView:self.view].y;
            if (pan.state == UIGestureRecognizerStateEnded && (dy > 140 || velocity > 900)) {
                [UIView animateWithDuration:0.25 delay:0 options:UIViewAnimationOptionCurveEaseIn animations:^{
                    self.view.transform = CGAffineTransformMakeTranslation(0, self.view.bounds.size.height);
                } completion:^(BOOL finished) {
                    [self dismissViewControllerAnimated:NO completion:nil];
                }];
            } else {
                [UIView animateWithDuration:0.4 delay:0 usingSpringWithDamping:0.8 initialSpringVelocity:0 options:0 animations:^{
                    self.view.transform = CGAffineTransformIdentity;
                } completion:nil];
            }
            break;
        }
        default:
            break;
    }
}

@end
