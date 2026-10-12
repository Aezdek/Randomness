#import "ARSettingsViewController.h"
#import "ARProxyClient.h"
#import "ARStore.h"
#import "ARTheme.h"
#import "ARIcons.h"

typedef NS_ENUM(NSInteger, ARSettingsSection) { ARSectionProxy, ARSectionData, ARSectionAbout };

@interface ARSettingsViewController () <UITextFieldDelegate>
@end

@implementation ARSettingsViewController {
    UITextField *_serverField;
    UITextField *_keyField;
    NSString *_status;
    UIColor *_statusColor;
    BOOL _testing;
}

- (instancetype)init {
    if ((self = [super initWithStyle:UITableViewStyleGrouped])) {
        self.title = @"Settings";
        self.tabBarItem = [[UITabBarItem alloc] initWithTitle:@"Settings" image:[ARIcons icon:ARIconSettings size:25] tag:2];
    }
    return self;
}

- (UITextField *)fieldWithPlaceholder:(NSString *)placeholder {
    UITextField *f = [UITextField new];
    f.attributedPlaceholder = [[NSAttributedString alloc] initWithString:placeholder attributes:@{NSForegroundColorAttributeName: [UIColor colorWithWhite:1 alpha:0.25]}];
    f.textColor = [ARTheme textPrimary];
    f.font = [ARTheme fontOfSize:16 weight:UIFontWeightRegular];
    f.keyboardAppearance = UIKeyboardAppearanceDark;
    f.autocorrectionType = UITextAutocorrectionTypeNo;
    f.autocapitalizationType = UITextAutocapitalizationTypeNone;
    f.clearButtonMode = UITextFieldViewModeWhileEditing;
    f.delegate = self;
    [f addTarget:self action:@selector(fieldChanged:) forControlEvents:UIControlEventEditingDidEnd];
    return f;
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.tableView.backgroundColor = [ARTheme background];
    self.tableView.separatorColor = [ARTheme separator];
    self.tableView.keyboardDismissMode = UIScrollViewKeyboardDismissModeOnDrag;

    _serverField = [self fieldWithPlaceholder:@"http://192.168.1.20:8765"];
    _serverField.keyboardType = UIKeyboardTypeURL;
    _serverField.returnKeyType = UIReturnKeyNext;
    _serverField.text = [ARProxyClient shared].baseURLString;

    _keyField = [self fieldWithPlaceholder:@"Optional"];
    _keyField.secureTextEntry = YES;
    _keyField.returnKeyType = UIReturnKeyDone;
    _keyField.text = [ARProxyClient shared].accessKey;
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    if (!_status && [ARProxyClient shared].configured) [self testConnection];
}

- (void)fieldChanged:(UITextField *)field {
    if (field == _serverField) {
        [ARProxyClient shared].baseURLString = field.text;
        field.text = [ARProxyClient shared].baseURLString;
    } else {
        [ARProxyClient shared].accessKey = field.text;
    }
}

- (BOOL)textFieldShouldReturn:(UITextField *)textField {
    if (textField == _serverField) {
        [_keyField becomeFirstResponder];
    } else {
        [textField resignFirstResponder];
        [self testConnection];
    }
    return YES;
}

- (void)testConnection {
    [self.view endEditing:YES];
    if (![ARProxyClient shared].configured) {
        [self setStatus:@"Enter the server address first" color:[ARTheme accent]];
        return;
    }
    _testing = YES;
    [self setStatus:@"Connecting…" color:[ARTheme textSecondary]];
    [[ARProxyClient shared] pingWithCompletion:^(NSDictionary *info, NSError *error) {
        self->_testing = NO;
        if (error) {
            [self setStatus:error.localizedDescription color:[ARTheme accent]];
        } else {
            NSString *version = [info[@"ytdlp"] isKindOfClass:[NSString class]] ? info[@"ytdlp"] : @"?";
            [self setStatus:[NSString stringWithFormat:@"Connected · yt-dlp %@", version] color:[UIColor colorWithRed:0.3 green:0.85 blue:0.5 alpha:1]];
        }
    }];
}

- (void)setStatus:(NSString *)status color:(UIColor *)color {
    _status = status;
    _statusColor = color;
    [self.tableView reloadRowsAtIndexPaths:@[[NSIndexPath indexPathForRow:2 inSection:ARSectionProxy]] withRowAnimation:UITableViewRowAnimationNone];
}

#pragma mark - Table

- (NSInteger)numberOfSectionsInTableView:(UITableView *)tableView { return 3; }

- (NSInteger)tableView:(UITableView *)tableView numberOfRowsInSection:(NSInteger)section {
    switch (section) {
        case ARSectionProxy: return 3;
        case ARSectionData: return 2;
        default: return 1;
    }
}

- (NSString *)tableView:(UITableView *)tableView titleForHeaderInSection:(NSInteger)section {
    switch (section) {
        case ARSectionProxy: return @"YouTube Proxy";
        case ARSectionData: return @"Stream";
        default: return nil;
    }
}

- (NSString *)tableView:(UITableView *)tableView titleForFooterInSection:(NSInteger)section {
    if (section == ARSectionProxy) {
        return @"Run aria_proxy.py on a computer on the same Wi-Fi and enter the address it prints. Music streams through it, so keep it running while you listen.";
    }
    return nil;
}

- (void)tableView:(UITableView *)tableView willDisplayHeaderView:(UIView *)view forSection:(NSInteger)section {
    if ([view isKindOfClass:[UITableViewHeaderFooterView class]]) ((UITableViewHeaderFooterView *)view).textLabel.textColor = [ARTheme textSecondary];
}

- (void)tableView:(UITableView *)tableView willDisplayFooterView:(UIView *)view forSection:(NSInteger)section {
    if ([view isKindOfClass:[UITableViewHeaderFooterView class]]) ((UITableViewHeaderFooterView *)view).textLabel.textColor = [ARTheme textSecondary];
}

- (UITableViewCell *)cellWithStyle:(UITableViewCellStyle)style {
    UITableViewCell *cell = [[UITableViewCell alloc] initWithStyle:style reuseIdentifier:nil];
    cell.backgroundColor = [ARTheme surface];
    cell.textLabel.textColor = [ARTheme textPrimary];
    cell.textLabel.font = [ARTheme fontOfSize:16 weight:UIFontWeightRegular];
    cell.detailTextLabel.textColor = [ARTheme textSecondary];
    cell.selectedBackgroundView = [UIView new];
    cell.selectedBackgroundView.backgroundColor = [ARTheme surfaceHighlight];
    return cell;
}

- (UITableViewCell *)fieldCell:(NSString *)label field:(UITextField *)field {
    UITableViewCell *cell = [self cellWithStyle:UITableViewCellStyleDefault];
    cell.selectionStyle = UITableViewCellSelectionStyleNone;
    cell.textLabel.text = label;
    field.frame = CGRectMake(96, 0, cell.contentView.bounds.size.width - 96 - 12, 44);
    field.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [cell.contentView addSubview:field];
    return cell;
}

- (UITableViewCell *)tableView:(UITableView *)tableView cellForRowAtIndexPath:(NSIndexPath *)indexPath {
    if (indexPath.section == ARSectionProxy) {
        if (indexPath.row == 0) return [self fieldCell:@"Server" field:_serverField];
        if (indexPath.row == 1) return [self fieldCell:@"Key" field:_keyField];
        UITableViewCell *cell = [self cellWithStyle:UITableViewCellStyleSubtitle];
        cell.textLabel.text = @"Test Connection";
        cell.textLabel.textColor = [ARTheme accent];
        cell.detailTextLabel.text = _status;
        cell.detailTextLabel.textColor = _statusColor;
        cell.detailTextLabel.numberOfLines = 2;
        return cell;
    }
    if (indexPath.section == ARSectionData) {
        UITableViewCell *cell = [self cellWithStyle:UITableViewCellStyleDefault];
        cell.textLabel.text = indexPath.row == 0 ? @"Clear Recently Played" : @"Clear Saved";
        cell.textLabel.textColor = [ARTheme accent];
        return cell;
    }
    UITableViewCell *cell = [self cellWithStyle:UITableViewCellStyleValue1];
    cell.selectionStyle = UITableViewCellSelectionStyleNone;
    cell.textLabel.text = @"Aria";
    cell.detailTextLabel.text = [NSString stringWithFormat:@"Version %@", [NSBundle mainBundle].infoDictionary[@"CFBundleShortVersionString"]];
    return cell;
}

- (CGFloat)tableView:(UITableView *)tableView heightForRowAtIndexPath:(NSIndexPath *)indexPath {
    return indexPath.section == ARSectionProxy && indexPath.row == 2 && _status ? 62 : 46;
}

- (void)tableView:(UITableView *)tableView didSelectRowAtIndexPath:(NSIndexPath *)indexPath {
    [tableView deselectRowAtIndexPath:indexPath animated:YES];
    if (indexPath.section == ARSectionProxy && indexPath.row == 2 && !_testing) {
        [self testConnection];
    } else if (indexPath.section == ARSectionData) {
        BOOL recent = indexPath.row == 0;
        UIAlertController *alert = [UIAlertController alertControllerWithTitle:recent ? @"Clear Recently Played?" : @"Clear Saved?"
                                                                       message:nil
                                                                preferredStyle:UIAlertControllerStyleAlert];
        [alert addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
        [alert addAction:[UIAlertAction actionWithTitle:@"Clear" style:UIAlertActionStyleDestructive handler:^(UIAlertAction *a) {
            if (recent) [[ARStore shared] clearRecent];
            else for (id track in [ARStore shared].savedTracks) [[ARStore shared] toggleSaved:track];
        }]];
        [self presentViewController:alert animated:YES completion:nil];
    }
}

@end
