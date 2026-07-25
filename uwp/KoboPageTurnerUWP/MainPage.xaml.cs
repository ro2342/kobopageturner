using System;
using KoboPageTurnerUWP.Services;
using Windows.UI.Core;
using Windows.UI.Xaml.Controls;

namespace KoboPageTurnerUWP
{
    public sealed partial class MainPage : Page
    {
        private readonly BleKeyboardService _bleKeyboard = new BleKeyboardService();

        public MainPage()
        {
            this.InitializeComponent();
            _bleKeyboard.SubscribedClientsChanged += BleKeyboard_SubscribedClientsChanged;
            Loaded += MainPage_Loaded;
        }

        private async void MainPage_Loaded(object sender, Windows.UI.Xaml.RoutedEventArgs e)
        {
            var supported = await _bleKeyboard.CheckPeripheralSupportAsync();
            if (!supported)
            {
                StatusText.Text = "This phone's Bluetooth doesn't support peripheral/GATT-server mode — this app can't work here.";
                return;
            }

            try
            {
                await _bleKeyboard.StartAsync();
                StatusText.Text = "Advertising as a keyboard. Open your Kobo's Bluetooth pairing screen and connect to this phone.";
            }
            catch (Exception ex)
            {
                StatusText.Text = "Failed to start Bluetooth advertising: " + ex.Message;
            }
        }

        private async void BleKeyboard_SubscribedClientsChanged(object sender, EventArgs e)
        {
            await Dispatcher.RunAsync(CoreDispatcherPriority.Normal, () =>
            {
                StatusText.Text = _bleKeyboard.HasSubscribedClient
                    ? "Connected — button presses will turn pages."
                    : "Advertising as a keyboard. Open your Kobo's Bluetooth pairing screen and connect to this phone.";
            });
        }

        private async void PreviousButton_Click(object sender, Windows.UI.Xaml.RoutedEventArgs e)
        {
            await _bleKeyboard.SendKeyAsync(HidKeyCode.LeftArrow);
        }

        private async void NextButton_Click(object sender, Windows.UI.Xaml.RoutedEventArgs e)
        {
            await _bleKeyboard.SendKeyAsync(HidKeyCode.RightArrow);
        }
    }
}
