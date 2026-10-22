from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.button import Button


class TestScreen(BoxLayout):
    def __init__(self, **kw):
        super().__init__(orientation="vertical", padding=50, **kw)
        self.add_widget(Label(text="ERIX TEST OK", font_size=40))
        self.btn = Button(text="Tekan saya", size_hint=(1, 0.3))
        self.btn.bind(on_release=self.on_tap)
        self.add_widget(self.btn)
        self.count = 0

    def on_tap(self, *_):
        self.count += 1
        self.btn.text = "Ditekan %d kali" % self.count


class TestApp(App):
    def build(self):
        return TestScreen()


if __name__ == "__main__":
    TestApp().run()
