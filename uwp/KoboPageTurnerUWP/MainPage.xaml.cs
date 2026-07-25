using System;
using KoboPageTurnerUWP.Services;
using Windows.UI;
using Windows.UI.Core;
using Windows.UI.Popups;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media;

namespace KoboPageTurnerUWP
{
    public sealed partial class MainPage : Page
    {
        private const int SwipeThreshold = 60;

        private readonly BleKeyboardService _bleKeyboard = new BleKeyboardService();
        private string _statusDetail = "Checking Bluetooth…";

        public MainPage()
        {
            this.InitializeComponent();
            _bleKeyboard.SubscribedClientsChanged += BleKeyboard_SubscribedClientsChanged;
            Loaded += MainPage_Loaded;
        }

        private async void MainPage_Loaded(object sender, Windows.UI.Xaml.RoutedEventArgs e)
        {
            await StartBluetoothAsync();
        }

        private async System.Threading.Tasks.Task StartBluetoothAsync()
        {
            SetStatus("Checking Bluetooth…", Colors.Gray);

            var supported = await _bleKeyboard.CheckPeripheralSupportAsync();
            if (!supported)
            {
                SetStatus("This phone's Bluetooth doesn't support peripheral/GATT-server mode — this app can't work here.", Colors.Red);
                return;
            }

            try
            {
                await _bleKeyboard.StartAsync();
                SetStatus("Advertising as a keyboard. Open your Kobo's Bluetooth pairing screen and connect to this phone.", Colors.Orange);
            }
            catch (Exception ex)
            {
                SetStatus("Failed to start Bluetooth advertising: " + ex.Message, Colors.Red);
            }
        }

        private void SetStatus(string detail, Color color)
        {
            _statusDetail = detail;
            StatusDot.Fill = new SolidColorBrush(color);
        }

        private async void BleKeyboard_SubscribedClientsChanged(object sender, EventArgs e)
        {
            await Dispatcher.RunAsync(CoreDispatcherPriority.Normal, () =>
            {
                if (_bleKeyboard.HasSubscribedClient)
                {
                    SetStatus("Connected — button presses will turn pages.", Colors.Green);
                }
                else
                {
                    SetStatus("Advertising as a keyboard. Open your Kobo's Bluetooth pairing screen and connect to this phone.", Colors.Orange);
                }
            });
        }

        private async void PreviousZone_Tapped(object sender, TappedRoutedEventArgs e)
        {
            await _bleKeyboard.SendKeyAsync(HidKeyCode.LeftArrow);
        }

        private async void NextZone_Tapped(object sender, TappedRoutedEventArgs e)
        {
            await _bleKeyboard.SendKeyAsync(HidKeyCode.RightArrow);
        }

        private async void RootGrid_ManipulationCompleted(object sender, Windows.UI.Xaml.Input.ManipulationCompletedRoutedEventArgs e)
        {
            var dx = e.Cumulative.Translation.X;
            if (dx > SwipeThreshold)
            {
                await _bleKeyboard.SendKeyAsync(HidKeyCode.LeftArrow);
            }
            else if (dx < -SwipeThreshold)
            {
                await _bleKeyboard.SendKeyAsync(HidKeyCode.RightArrow);
            }
        }

        private async void InfoButton_Click(object sender, Windows.UI.Xaml.RoutedEventArgs e)
        {
            var dialog = new MessageDialog(
                _statusDetail + "\n\nKobo Page Turner turns the phone into a Bluetooth keyboard so the Kobo's own \"Bluetooth accessories\" page-turn support recognizes it. Pair it from the Kobo's Bluetooth settings like any keyboard, then tap or swipe left/right on this screen.",
                "Kobo Page Turner");
            await dialog.ShowAsync();
        }

        private async void RestartButton_Click(object sender, Windows.UI.Xaml.RoutedEventArgs e)
        {
            _bleKeyboard.Stop();
            await StartBluetoothAsync();
        }
    }
}
