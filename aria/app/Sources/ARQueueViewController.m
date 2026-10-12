#import "ARQueueViewController.h"
#import "ARTrackCell.h"
#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARTheme.h"

@implementation ARQueueViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    self.largeTitle = @"Up Next";
    self.navigationItem.rightBarButtonItem = [[UIBarButtonItem alloc] initWithBarButtonSystemItem:UIBarButtonSystemItemDone target:self action:@selector(done)];
    self.navigationController.navigationBar.barStyle = UIBarStyleBlack;
}

- (void)done {
    [self dismissViewControllerAnimated:YES completion:nil];
}

- (void)playerDidChange {
    [self.tableView reloadData];
    [super playerDidChange];
}

- (NSInteger)queueIndexForIndexPath:(NSIndexPath *)ip {
    ARPlayer *player = [ARPlayer shared];
    return ip.section == 0 ? player.index : player.index + 1 + ip.row;
}

- (ARTrack *)trackAtIndexPath:(NSIndexPath *)ip {
    NSArray *queue = [ARPlayer shared].queue;
    NSInteger i = [self queueIndexForIndexPath:ip];
    return i < (NSInteger)queue.count ? queue[i] : nil;
}

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tableView { return 2; }

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    ARPlayer *player = [ARPlayer shared];
    if (!player.currentTrack) return 0;
    return section == 0 ? 1 : MAX(0, (NSInteger)player.queue.count - player.index - 1);
}

- (CGFloat)tableView:(UITableView *)tableView heightForHeaderInSection:(NSInteger)section {
    return section == 1 && [self tableView:tableView numberOfRowsInSection:1] > 0 ? 46 : 0;
}

- (UIView *)tableView:(UITableView *)tableView viewForHeaderInSection:(NSInteger)section {
    if (section != 1) return nil;
    ARPlayer *player = [ARPlayer shared];
    NSString *mode = player.shuffle ? @"Shuffled" : player.repeatMode == ARRepeatAll ? @"Repeating" : nil;
    return [self sectionHeaderWithTitle:mode ? [NSString stringWithFormat:@"Up Next · %@", mode] : @"Up Next"];
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    ARTrackCell *cell = [tableView dequeueReusableCellWithIdentifier:@"track" forIndexPath:indexPath];
    [cell configureWithTrack:[self trackAtIndexPath:indexPath]];
    return cell;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    if (indexPath.section == 0) [[ARPlayer shared] togglePlayPause];
    else [[ARPlayer shared] jumpToIndex:[self queueIndexForIndexPath:indexPath]];
}

- (BOOL)tableView:(UITableView *)tableView canEditRowAtIndexPath:(NSIndexPath *)indexPath {
    return indexPath.section == 1;
}

- (void)tableView:(UITableView *)tableView commitEditingStyle:(UITableViewCellEditingStyle)style forRowAtIndexPath:(NSIndexPath *)indexPath {
    if (style == UITableViewCellEditingStyleDelete) [[ARPlayer shared] removeTrackAtIndex:[self queueIndexForIndexPath:indexPath]];
}

- (NSString *)tableView:(UITableView *)tableView titleForDeleteConfirmationButtonForRowAtIndexPath:(NSIndexPath *)indexPath {
    return @"Remove";
}

@end
